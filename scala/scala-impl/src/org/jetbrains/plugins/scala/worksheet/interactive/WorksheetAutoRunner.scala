package org.jetbrains.plugins.scala
package worksheet.interactive

import com.intellij.openapi.components.AbstractProjectComponent
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.{DocumentAdapter, DocumentEvent, DocumentListener}
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.newvfs.FileAttribute
import com.intellij.problems.WolfTheProblemSolver
import com.intellij.psi.{PsiDocumentManager, PsiFile, PsiWhiteSpace}
import com.intellij.util.Alarm
import com.intellij.util.containers.ContainerUtil
import org.jetbrains.plugins.scala.settings.ScalaProjectSettings
import worksheet.actions.{RunWorksheetAction, WorksheetFileHook}
import worksheet.processor.{FileAttributeUtilCache, WorksheetCompiler, WorksheetPerFileConfig}
import worksheet.runconfiguration.WorksheetCache
import worksheet.server.WorksheetProcessManager

/**
 * User: Dmitry.Naydanov
 * Date: 01.04.14.
 */
object WorksheetAutoRunner extends WorksheetPerFileConfig {
  val RUN_DELAY_MS_MAXIMUM = 3000
  val RUN_DELAY_MS_MINIMUM = 700
  
  private val AUTORUN = new FileAttribute("ScalaWorksheetAutoRun", 1, true)

  def isSetEnabled(file: PsiFile): Boolean = FileAttributeUtilCache.readAttribute(AUTORUN, file) contains enabled

  def isSetDisabled(file: PsiFile): Boolean = FileAttributeUtilCache.readAttribute(AUTORUN, file) contains disabled

  def setAutorun(file: PsiFile, autorun: Boolean) {
    setEnabled(file, AUTORUN, autorun)
  }

  def getInstance(project: Project): WorksheetAutoRunner = project.getComponent(classOf[WorksheetAutoRunner])
}

class WorksheetAutoRunner(project: Project, woof: WolfTheProblemSolver) extends AbstractProjectComponent(project) {
  private val listeners = ContainerUtil.createConcurrentWeakMap[Document, DocumentListener]()
  private val myAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, project)
  private lazy val settings = ScalaProjectSettings.getInstance(project)

  override def getComponentName: String = "WorksheetAutoRunner"

  def getAutoRunDelay: Int = ScalaProjectSettings.getInstance(project).getAutoRunDelay
  
  def addListener(document: Document) {
    if (listeners.get(document) == null) {
      val listener = new MyDocumentAdapter(document)

      document addDocumentListener listener
      listeners.put(document, listener)
    }
  }

  def removeListener(document: Document) {
    val listener = listeners remove document
    if (listener != null) document removeDocumentListener listener
  }
  
  def replExecuted(document: Document, offset: Int) {
    listeners.get(document) match {
      case myAdapter: MyDocumentAdapter =>
        myAdapter.updateOffset(offset)
      case _ => 
    }
  }

  private class MyDocumentAdapter(document: Document) extends DocumentListener {
    private val documentManager: PsiDocumentManager = PsiDocumentManager getInstance project
    private var lastProcessedOffset = 0

    @inline private def isDisabledOn(file: PsiFile) = {
      WorksheetAutoRunner.isSetDisabled(file) || !settings.isInteractiveMode && !WorksheetAutoRunner.isSetEnabled(file)
    }

    def updateOffset(offset: Int) {
      lastProcessedOffset = offset
    }
    
    override def documentChanged(e: DocumentEvent) {
      if (project.isDisposed) return
      
      val psiFile = documentManager getPsiFile document
      val offset = e.getOffset
      val isRepl = WorksheetCompiler isWorksheetReplMode psiFile 

      if (isRepl) {
        if (offset < lastProcessedOffset) WorksheetFileHook.getEditorFrom(FileEditorManager getInstance project, psiFile.getVirtualFile) foreach (
          ed => WorksheetCache.getInstance(project).setLastProcessedIncremental(ed, None) )
      }

      if (isDisabledOn(psiFile)) return

      val virtualFile = psiFile.getVirtualFile

      val fragment = e.getNewFragment
      val isTrashEvent = isRepl && fragment.length() == 0 && e.getOffset + 1 >= e.getDocument.getTextLength && e.getOldFragment.length() == 0

      if (!isTrashEvent) myAlarm.cancelAllRequests()

      val isReplWrongChar = isRepl && {
        val l = fragment.length()

        l < 1 || fragment.charAt(l - 1) != '\n'
      }
      
      if (woof.hasSyntaxErrors(virtualFile) || WorksheetProcessManager.running(virtualFile) || isReplWrongChar) return

      myAlarm.addRequest(new Runnable {
        override def run() {
          if (!psiFile.isValid) return 
          
          if (isRepl) psiFile findElementAt offset match {
            case null => //it means caret is at the end 
            case ws: PsiWhiteSpace if ws.getParent == psiFile =>
            case _ => return
          }
          
          if (!woof.hasSyntaxErrors(virtualFile) && !WorksheetProcessManager.running(virtualFile))
            RunWorksheetAction.runCompiler(project, auto = true)
        }
      }, if (isRepl) getAutoRunDelay/2 else getAutoRunDelay, true)
    }
  }
}