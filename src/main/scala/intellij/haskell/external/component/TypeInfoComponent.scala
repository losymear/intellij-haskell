/*
 * Copyright 2014-2018 Rik van der Kleij
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package intellij.haskell.external.component

import java.util.concurrent.TimeUnit

import com.github.blemale.scaffeine.{AsyncLoadingCache, Scaffeine}
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.SelectionModel
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.{PsiElement, PsiFile}
import intellij.haskell.external.repl.StackReplsManager
import intellij.haskell.psi._
import intellij.haskell.util.index.HaskellFilePathIndex
import intellij.haskell.util.{LineColumnPosition, ScalaUtil}

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future, TimeoutException}

private[component] object TypeInfoComponent {

  import intellij.haskell.external.component.TypeInfoComponentResult._

  private case class Key(psiFile: PsiFile, startLineNr: Int, startColumnNr: Int, endLineNr: Int, endColumnNr: Int, expression: String)

  private final val Cache: AsyncLoadingCache[Key, TypeInfoResult] = Scaffeine().buildAsync((k: Key) => findTypeInfoResult(k))

  def findTypeInfoForElement(psiElement: PsiElement): Option[TypeInfoResult] = {
    val result = if (psiElement.isValid) {
      ApplicationManager.getApplication.runReadAction(ScalaUtil.computable {
        for {
          qne <- HaskellPsiUtil.findQualifiedNameParent(psiElement)
          to = qne.getTextOffset
          pf <- Option(psiElement.getContainingFile)
          sp <- LineColumnPosition.fromOffset(pf, to)
          ep <- LineColumnPosition.fromOffset(pf, to + qne.getText.length)
        } yield Key(pf, sp.lineNr, sp.columnNr, ep.lineNr, ep.columnNr, qne.getName)
      }).map(findTypeInfo)
    } else {
      None
    }
    result
  }

  def findTypeInfoForSelection(psiFile: PsiFile, selectionModel: SelectionModel): Option[TypeInfoResult] = {
    val result = for {
      sp <- LineColumnPosition.fromOffset(psiFile, selectionModel.getSelectionStart)
      ep <- LineColumnPosition.fromOffset(psiFile, selectionModel.getSelectionEnd)
    } yield findTypeInfo(Key(psiFile, sp.lineNr, sp.columnNr, ep.lineNr, ep.columnNr, selectionModel.getSelectedText))
    result
  }

  def invalidate(psiFile: PsiFile): Unit = {
    Cache.synchronous().asMap().filter(_._1.psiFile == psiFile).keys.foreach(Cache.synchronous().invalidate)
  }

  private def findTypeInfoResult(key: Key): TypeInfoResult = {
    val psiFile = key.psiFile
    if (LoadComponent.isBusy(psiFile)) {
      Left(ReplIsBusy)
    } else {
      val moduleName = HaskellFilePathIndex.findModuleName(psiFile, GlobalSearchScope.projectScope(psiFile.getProject))
      val typeInfo = StackReplsManager.getProjectRepl(key.psiFile).flatMap(_.findTypeInfo(moduleName, key.psiFile, key.startLineNr, key.startColumnNr, key.endLineNr, key.endColumnNr, key.expression)) match {
        case Some(output) => output.stdoutLines.headOption.filterNot(_.trim.isEmpty).map(ti => Right(TypeInfo(ti, output.stderrLines.nonEmpty))).getOrElse(Left(NoInfoAvailable))
        case _ => Left(ReplNotAvailable)
      }
      typeInfo
    }
  }

  private def findTypeInfo(key: Key): TypeInfoResult = {
    Cache.getIfPresent(key) match {
      case Some(r) => wait(r)
      case None =>
        if (LoadComponent.isModuleLoaded(HaskellFilePathIndex.findModuleName(key.psiFile, GlobalSearchScope.projectScope(key.psiFile.getProject)), key.psiFile)) {
          val result = wait(Cache.get(key))
          result match {
            case Right(_) => result
            case Left(NoInfoAvailable) =>
              result
            case Left(ReplNotAvailable) =>
              Cache.synchronous().invalidate(key)
              result
            case Left(ReplIsBusy) =>
              Cache.synchronous().invalidate(key)
              result
          }
        }
        else {
          Left(NoInfoAvailable)
        }
    }
  }

  private final val Timeout = Duration.create(100, TimeUnit.MILLISECONDS)

  private def wait(f: => Future[TypeInfoResult]) = {
    try {
      Await.result(f, Timeout)
    } catch {
      case _: TimeoutException => Left(ReplIsBusy)
    }
  }
}

object TypeInfoComponentResult {

  type TypeInfoResult = Either[NoInfo, TypeInfo]

  case class TypeInfo(typeSignature: String, withFailure: Boolean)

}
