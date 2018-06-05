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

package intellij.haskell.action

import com.intellij.openapi.actionSystem.{AnAction, AnActionEvent}
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.{PsiElement, PsiFile}
import intellij.haskell.editor.HaskellCompletionContributor
import intellij.haskell.external.component.{HaskellComponentsManager, StackProjectManager}
import intellij.haskell.psi._
import intellij.haskell.util.index.HaskellFilePathIndex
import intellij.haskell.util.{HaskellEditorUtil, ScalaUtil, StringUtil}

class ShowTypeAction extends AnAction {

  override def update(actionEvent: AnActionEvent) {
    HaskellEditorUtil.enableAction(onlyForProjectFile = true, actionEvent)
  }

  def actionPerformed(actionEvent: AnActionEvent) {
    if (!StackProjectManager.isBuilding(actionEvent.getProject)) {
      ActionUtil.findActionContext(actionEvent).foreach(actionContext => {
        val editor = actionContext.editor
        val psiFile = actionContext.psiFile

        actionContext.selectionModel match {
          case Some(sm) => HaskellComponentsManager.findTypeInfoForSelection(psiFile, sm) match {
            case Some(Right(info)) => HaskellEditorUtil.showHint(editor, StringUtil.escapeString(info.typeSignature))
            case _ => HaskellEditorUtil.showHint(editor, "Could not determine type for selection")
          }
          case _ => ()
            Option(psiFile.findElementAt(editor.getCaretModel.getOffset)).foreach { psiElement =>
              ShowTypeAction.showTypeHint(actionContext.project, editor, psiElement, psiFile)
            }
        }
      })
    }
    else {
      HaskellEditorUtil.showHaskellSupportIsNotAvailableWhileBuilding(actionEvent.getProject)
    }
  }
}

object ShowTypeAction {

  def showTypeInStatusBar(project: Project, psiElement: PsiElement, psiFile: PsiFile): Unit = {
    HaskellComponentsManager.findTypeInfoForElement(psiElement) match {
      case Some(Right(info)) =>
        val typeSignatureFromScope =
          if (info.withFailure) {
            getTypeSignatureFromScopeInReadAction(psiElement, psiFile)
          } else {
            None
          }
        showTypeSignatureInStatusBar(project, typeSignatureFromScope.getOrElse(info.typeSignature))
      case _ =>
        getTypeSignatureFromScopeInReadAction(psiElement, psiFile) match {
          case Some(typeSignature) => showTypeSignatureInStatusBar(project, typeSignature)
          case None => ()
        }
    }
  }

  def showTypeHint(project: Project, editor: Editor, psiElement: PsiElement, psiFile: PsiFile, sticky: Boolean = false): Unit = {
    HaskellComponentsManager.findTypeInfoForElement(psiElement) match {
      case Some(Right(info)) =>
        val typeSignatureFromScope =
          if (info.withFailure) {
            getTypeSignatureFromScope(psiFile, psiElement)
          } else {
            None
          }

        showTypeSignatureMessages(project, editor, sticky, typeSignatureFromScope.getOrElse(info.typeSignature))
      case _ =>
        getTypeSignatureFromScope(psiFile, psiElement) match {
          case Some(typeSignature) => showTypeSignatureMessages(project, editor, sticky, typeSignature)
          case None => showNoTypeInfoHint(editor, psiElement)
        }
    }
  }

  private def getTypeSignatureFromScopeInReadAction(psiElement: PsiElement, psiFile: PsiFile) = {
    ApplicationManager.getApplication.runReadAction(ScalaUtil.computable(getTypeSignatureFromScope(psiFile, psiElement)))
  }

  private def showTypeSignatureMessages(project: Project, editor: Editor, sticky: Boolean, typeSignature: String) = {
    showTypeSignatureInStatusBar(project, typeSignature)
    HaskellEditorUtil.showHint(editor, StringUtil.escapeString(typeSignature), sticky)
  }

  private def showTypeSignatureInStatusBar(project: Project, typeSignature: String) = {
    HaskellEditorUtil.showStatusBarInfoMessage(project, typeSignature)
  }

  private def getTypeSignatureFromScope(psiFile: PsiFile, psiElement: PsiElement) = {
    if (HaskellPsiUtil.findExpressionParent(psiElement).isDefined) {
      HaskellPsiUtil.findQualifiedNameParent(psiElement).flatMap(qualifiedNameElement => {
        val name = qualifiedNameElement.getName
        val moduleName = findModuleName(psiFile)
        HaskellCompletionContributor.getAvailableModuleIdentifiers(psiFile, moduleName).find(_.name == name).map(_.declaration).
          orElse(HaskellPsiUtil.findHaskellDeclarationElements(psiFile).find(_.getIdentifierElements.exists(_.getName == name)).map(_.getText.replaceAll("""\s+""", " ")))
      })
    } else {
      None
    }
  }

  private def findModuleName(psiFile: PsiFile): Option[String] = {
    HaskellFilePathIndex.findModuleName(psiFile, GlobalSearchScope.projectScope(psiFile.getProject))
  }

  private def showNoTypeInfoHint(editor: Editor, psiElement: PsiElement): Unit = {
    HaskellEditorUtil.showHint(editor, s"Could not determine type for ${StringUtil.escapeString(psiElement.getText)}")
  }
}
