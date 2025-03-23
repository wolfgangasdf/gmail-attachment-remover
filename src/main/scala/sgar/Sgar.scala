
/*
    This file is part of SGAR - Scala Gmail attachment Remover.

    SGAR is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    SGAR is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with Foobar.  If not, see <http://www.gnu.org/licenses/>.
 */

package sgar

import scalafx.Includes._
import scalafx.application.JFXApp3
import scalafx.beans.property.ReadOnlyStringWrapper
import scalafx.collections.ObservableBuffer
import scalafx.concurrent.{Service, WorkerStateEvent}
import scalafx.event.ActionEvent
import scalafx.geometry.{Insets, Pos}
import scalafx.scene.Scene
import scalafx.scene.control.Alert.AlertType
import scalafx.scene.control.Button._
import scalafx.scene.control.TreeTableColumn._
import scalafx.scene.control._
import scalafx.scene.layout.HBox._
import scalafx.scene.layout._
import scalafx.stage._
import sgar.GmailStuff.{Bodypart, ToDelete}
import sgar.Helpers.MyWorker

import java.awt.{Desktop, Taskbar}
import java.io._
import java.net.URI
import java.util.jar.JarFile
import java.util.{Date, Properties}
import javax.imageio.ImageIO
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.jdk.CollectionConverters._

class MainScene(stage: Stage, props: Properties) extends Scene {

  private val home = "https://github.com/wolfgangasdf/gmail-attachment-remover"
  private val versionInfo = s"SGAR build time: ${Helpers.getClassBuildTime.toString}"
  private val space = 4.0
  private var currentAccount: String = _

  def updateAccountsGuiFromProperties(props: Properties): Unit = {
    val accprop = props.getProperty("accounts", "")
    if (cbaccount.items != null) {
      cbaccount.items.get.clear()
      if (accprop != "") for (a <- accprop.split(",,,")) {
        cbaccount.items.get += a
      }
      cbaccount.getSelectionModel.select(props.getProperty("lastaccount", "0").toInt)
    }
    cbCaffeinate.selected.value = props.getProperty("caffeinate", "false").toBoolean
  }

  def updateAccountsPropertiesFromGui(props: Properties): Unit = {
    props.put("accounts", cbaccount.items.get.mkString(",,,"))
    props.put("lastaccount", cbaccount.getSelectionModel.getSelectedIndex.toString)
    props.put("caffeinate", cbCaffeinate.selected.value.toString)
  }

  def updatePropertiesForAccount(props: Properties): Unit = {
    if (currentAccount != null) {
      props.put(currentAccount + "-dobackup", cbDobackup.selected.value.toString)
      props.put(currentAccount + "-backupdir", tfbackupdir.text.value)
      props.put(currentAccount + "-minbytes", tfminbytes.text.value)
      props.put(currentAccount + "-limit", tflimit.text.value)
      props.put(currentAccount + "-label", tflabel.text.value)
      props.put(currentAccount + "-allmailfolder", tfallmailfolder.text.value)
      props.put(currentAccount + "-trashfolder", tftrashfolder.text.value)
      props.put(currentAccount + "-gmailsearch", cbgmailsearch.getValue)
      props.put(currentAccount + "-password", tfpassword.getText)
    }
  }

  private def updateGuiForAccount(): Unit = {
    currentAccount = cbaccount.getValue
    if (currentAccount != null) {
      cbDobackup.selected.value = java.lang.Boolean.parseBoolean(props.getProperty(currentAccount + "-dobackup", "true"))
      tfbackupdir.text = props.getProperty(currentAccount + "-backupdir", "???")
      tfminbytes.text = props.getProperty(currentAccount + "-minbytes", "10000")
      tflimit.text = props.getProperty(currentAccount + "-limit", "10")
      tflabel.text = props.getProperty(currentAccount + "-label", "removeattachments")
      tfallmailfolder.text = props.getProperty(currentAccount + "-allmailfolder", "All Mail")
      tftrashfolder.text = props.getProperty(currentAccount + "-trashfolder", "Trash")
      cbgmailsearch.setValue(props.getProperty(currentAccount + "-gmailsearch", "???")) // don't use value = !
      tfpassword.text = props.getProperty(currentAccount + "-password", "")
    }
  }

  class TreeThing(val toDelete: ToDelete, val bodypart: Bodypart) {
    def getColumn(i: Int): String = {
      if (bodypart == null) {
        List(toDelete.gmid.toString, toDelete.subject, toDelete.date, toDelete.from)(i)
      } else {
        List(bodypart.contentID, bodypart.filename, bodypart.filesize.toString, bodypart.contentType)(i)
      }
    }
  }

  private val tiroot = new TreeItem[TreeThing](new TreeThing(null, null))
  tiroot.setExpanded(true)

  private val ttv: TreeTableView[TreeThing] = new TreeTableView[TreeThing] {
    vgrow = Priority.Always
    columnResizePolicy = TreeTableView.ConstrainedResizePolicy
    val colheaders: Seq[String] = List("Gmail ID/Content ID", "Subject/Filename", "Date/Size", "Sender/Type")
    for (i <- colheaders.indices) {
      columns += new TreeTableColumn[TreeThing, String](colheaders(i)) {
        cellValueFactory = { p => ReadOnlyStringWrapper(p.value.getValue.getColumn(i)) }
      }
    }
    selectionModel.value.selectionMode = SelectionMode.Multiple
    root = tiroot
    showRoot = false
  }

  private val btRemoveRows: Button = new Button("Remove selected rows") {
    tooltip = new Tooltip {
      text = "... to prevent attachments from being removed"
    }
    onAction = (_: ActionEvent) => {
      for (it <- ttv.getSelectionModel.getSelectedItems.toSeq.reverse) { // important that reversed!
        if (it.getValue.bodypart == null) { // whole message
          tiroot.getChildren.remove(it)
        } else { // just one attachment
          it.getParent.getChildren.remove(it)
        }
      }
    }
  }

  private val listPane: VBox = new VBox(space) {
    vgrow = Priority.Always
    maxHeight = Double.MaxValue
    fillWidth = true
    children ++= Seq(
      ttv,
      new HBox {
        children ++= Seq(
          btRemoveRows
        )
      }
    )
  }

  private val cbaccount: ChoiceBox[String] = new ChoiceBox[String] {
    hgrow = Priority.Always
    maxWidth = Double.MaxValue
    tooltip = new Tooltip {
      text = "Gmail email adress"
    }
    items = new ObservableBuffer[String]()
    value.onChange {
      updatePropertiesForAccount(props) // uses old account
      updateGuiForAccount()
    }
  }

  private val tfpassword: PasswordField = new PasswordField {
    prefWidth = 140
    text = ""
    tooltip = new Tooltip {
      text = "Password is saved in clear text in settings file."
    }
  }

  println(versionInfo)
  Sgar.loadSettings()
  println("Logging to file " + Sgar.logfile.getPath)

  private val tfbackupdir: TextField = new TextField {
    hgrow = Priority.Always
    maxWidth = Double.MaxValue
    text = ""
    tooltip = new Tooltip {
      text = "Emails are saved here before attachments are removed"
    }
  }
  private val tfminbytes: TextField = new TextField {
    text = ""; tooltip = new Tooltip {
      text = "Minimum size of attachments for being considered for removal"
    }
  }
  private val tflimit: TextField = new TextField {
    text = ""; tooltip = new Tooltip {
      text = "Maximum number of emails to be considered in a single run"
    }
  }
  private val tflabel: TextField = new TextField {
    hgrow = Priority.Always
    maxWidth = Double.MaxValue
    text = ""
    tooltip = new Tooltip {
      text = "Only consider emails having this label; consider all if empty"
    }
  }
  private val tfallmailfolder: TextField = new TextField {
    hgrow = Priority.Always
    maxWidth = Double.MaxValue
    text = ""
    tooltip = new Tooltip {
      text = "Gmail's \"All Mail\" folder name (change for other languages)"
    }
  }
  private val tftrashfolder: TextField = new TextField {
    hgrow = Priority.Always
    maxWidth = Double.MaxValue
    text = ""
    tooltip = new Tooltip {
      text = "Gmail's \"Trash\" folder name (change for other languages)"
    }
  }
  val cbCaffeinate = new CheckBox("Run caffeinate")
  private val cbDobackup = new CheckBox("Backup messages (slow)")
  private val cbgmailsearch: ComboBox[String] = new ComboBox[String] {
    hgrow = Priority.Always
    maxWidth = Double.MaxValue
    tooltip = new Tooltip {
      text = "mind that ' label:<label>' is appended!"
    }
    val strings: ObservableBuffer[String] = ObservableBuffer("size:1MB has:attachment -in:inbox", "size:1MB has:attachment older_than:12m -in:inbox")
    items = strings
    editable = true
  }

  private val bSelectbackupdir: Button = new Button("Select...") {
    onAction = (_: ActionEvent) => {
      val fcbackupdir = new DirectoryChooser {
        title = "Pick backup folder..."
      }
      tfbackupdir.text = fcbackupdir.showDialog(stage).getAbsolutePath
    }
  }

  def setButtons(flist: Boolean = false, getemails: Boolean = false, rmattach: Boolean = false): Unit = {
    Helpers.runUI {
      btFolderList.disable = !flist
      btGetEmails.disable = !getemails
      btRemoveAttachments.disable = !rmattach
      btRemoveRows.disable = !(getemails && rmattach)
    }
  }

  private def setupGmail(): Unit = {
    updatePropertiesForAccount(props)
    GmailStuff.backupdir = new File(tfbackupdir.text.value)
    GmailStuff.doBackup = cbDobackup.selected.value
    GmailStuff.username = cbaccount.getValue
    GmailStuff.password = tfpassword.getText
    GmailStuff.label = tflabel.text.value
    GmailStuff.allmailfolder = tfallmailfolder.text.value
    GmailStuff.trashfolder = tftrashfolder.text.value
    GmailStuff.gmailsearch = cbgmailsearch.getValue
    GmailStuff.minbytes = tfminbytes.text.value.toInt
    GmailStuff.limit = tflimit.text.value.toInt
  }

  private val btFolderList: Button = new Button("Get gmail folder list") {
    tooltip = new Tooltip {
      text = "Mainly for debug purposes. Output is shown in log."
    }
    onAction = (_: ActionEvent) => {
      setButtons()
      setupGmail()
      Future {
        try {
          GmailStuff.getAllFolders
        } catch {
          case e: Exception => showGmailErrorCleanup(e)
        } finally {
          setButtons(flist = true, getemails = true)
        }
      }
    }
  }

  private val btAbout: Button = new Button("About") {
    tooltip = new Tooltip {
      text = s"Open website $home"
    }
    onAction = (_: ActionEvent) => {
      println(versionInfo)
      println(s"Open $home ...")
      if (Desktop.isDesktopSupported) {
        val desktop = Desktop.getDesktop
        if (desktop.isSupported(Desktop.Action.BROWSE)) {
          desktop.browse(new URI(home))
        }
      }
    }
  }

  private def showGmailErrorCleanup(e: Throwable): Unit = {
    println("Exception " + e.getMessage)
    e.printStackTrace()
    Helpers.runUI {
      new Alert(AlertType.Error, "Error communicating with gmail.\nError: " + e.getMessage).showAndWait()
    }
  }

  private val btGetEmails: Button = new Button("Find emails") {
    tooltip = new Tooltip {
      text = "Find emails based on selected criteria"
    }
    onAction = (_: ActionEvent) => {
      setButtons()
      setupGmail()
      tiroot.children.clear()
      val task = GmailStuff.getToDelete
      task.onSucceeded = () => {
        val dellist = task.get()
        Helpers.runUI {
          for (todel <- dellist) {
            val ti = new TreeItem[TreeThing](new TreeThing(todel, null))
            for (bp <- todel.bodyparts) {
              ti.children += new TreeItem[TreeThing](new TreeThing(todel, bp))
            }
            tiroot.children += ti
          }
        }
        setButtons(flist = true, getemails = true, rmattach = true)
        Helpers.caffeinate(false)
      }
      task.onCancelled = () => {
        setButtons(flist = true, getemails = true)
        Helpers.caffeinate(false)
      }
      task.onFailed = () => {
        showGmailErrorCleanup(task.getException)
        setButtons(flist = true, getemails = true)
        Helpers.caffeinate(false)
      }
      Helpers.caffeinate(true)
      new MyWorker[ListBuffer[ToDelete]]("Find emails...", task).runInBackground()
    }
  }

  private val btRemoveAttachments: Button = new Button("Remove attachments") {
    tooltip = new Tooltip {
      text = "Remove all attachments shown in the table below"
    }
    onAction = (_: ActionEvent) => {
      setButtons()
      setupGmail()
      val dellist = new ListBuffer[ToDelete]
      for (timail <- tiroot.children) {
        val bplist = new ListBuffer[Bodypart]
        for (tibps <- timail.getChildren) {
          bplist += tibps.getValue.bodypart
        }
        if (bplist.nonEmpty) {
          val td = timail.getValue.toDelete
          td.bodyparts.clear()
          td.bodyparts ++= bplist
          dellist += td
        }
      }
      val task = GmailStuff.doDelete(dellist)
      task.onSucceeded = () => {
        Helpers.runUI {
          tiroot.children.clear()
        }
        setButtons(flist = true, getemails = true)
        Helpers.caffeinate(false)
      }
      task.onCancelled = () => {
        Helpers.runUI {
          tiroot.children.clear()
        }
        setButtons(flist = true, getemails = true)
        Helpers.caffeinate(false)
      }
      task.onFailed = () => {
        Helpers.runUI {
          tiroot.children.clear()
        }
        showGmailErrorCleanup(task.getException)
        setButtons(flist = true, getemails = true)
        Helpers.caffeinate(false)
      }
      Helpers.caffeinate(true)
      new MyWorker[Unit]("Remove attachments...", task).runInBackground()
    }
  }

  private val settingsPane: VBox = new VBox(space) {
    children ++= List(
      new HBox(space) {
        alignment = Pos.CenterLeft
        children ++= List(
          new Label("Gmail Account: "),
          cbaccount,
          tfpassword,
          new Button("Get app password") {
            onAction = (_: ActionEvent) => {
              val link = "https://security.google.com/settings/security/apppasswords"
              val a = new Alert(AlertType.Information)
              val ta = new TextArea("Clicking OK will open google settings. Select \"Mail\", \"gmail-attachment-remover\", and copy the password in the field.\n" +
                "If this doesn't work, open this link in a browser:\n" + link)
              ta.editable = false
              ta.wrapText = true
              a.getDialogPane.content = ta
              a.showAndWait()
              if (Desktop.isDesktopSupported) {
                val desktop = Desktop.getDesktop
                if (desktop.isSupported(Desktop.Action.BROWSE)) {
                  desktop.browse(new URI(link))
                }
              }
            }
          },
          new Button("Add") {
            tooltip = new Tooltip {
              text = "Add a new Gmail account"
            }
            onAction = (_: ActionEvent) => {
              val dialog = new TextInputDialog()
              dialog.setTitle("New account")
              dialog.setHeaderText("Enter gmail address")
              val res = dialog.showAndWait()
              res.foreach(r => {
                cbaccount.getItems.add(r)
                cbaccount.setValue(r)
              })
            }
          },
          new Button("Remove") {
            tooltip = new Tooltip {
              text = "Remove the selected Gmail account from the list"
            }
            onAction = (_: ActionEvent) => {
              val iter = props.entrySet.iterator
              while (iter.hasNext) {
                val entry = iter.next()
                if (entry.getKey.asInstanceOf[String].startsWith(currentAccount + "-")) iter.remove()
              }
              currentAccount = null // prevent from saving deleted account to properties
              cbaccount.items.get -= cbaccount.getValue
              if (cbaccount.items.get.nonEmpty) cbaccount.getSelectionModel.selectFirst()
            }
          }
        )
        setHgrow(cbaccount, Priority.Always)
      },
      new HBox(space) {
        alignment = Pos.CenterLeft; children ++= List(cbDobackup, new Label("Backupfolder: "), tfbackupdir, bSelectbackupdir)
      },
      new HBox(space) {
        alignment = Pos.CenterLeft
        children ++= List(
          new Label("Gmail label: "), tflabel,
          new Label("\"All mail\" folder: "), tfallmailfolder,
          new Label("\"Trash\" folder: "), tftrashfolder)
      },
      new HBox(space) {
        alignment = Pos.CenterLeft; children ++= List(new Label("Gmail search: "), cbgmailsearch)
      },
      new HBox(space) {
        alignment = Pos.CenterLeft;
        children ++= List(
          new Label("Minimum Attachment size (bytes): "),
          tfminbytes,
          new Label("     Limit # messages: "),
          tflimit,
          cbCaffeinate
        )
      },
      new HBox(space) {
        alignment = Pos.Center
        children += new Button("Quit") {
          onAction = (_: ActionEvent) => {
            Sgar.stopApp()
          }
        }
        children ++= List(btGetEmails, btRemoveAttachments, btFolderList, btAbout)
      }
    )
  }

  private val cont: VBox = new VBox(space) {
    children ++= List(settingsPane, listPane, Sgar.logView)
    padding = Insets(2 * space)
  }

  stylesheets = List(getClass.getResource("/main.css").toExternalForm)

  content = cont
  cont.prefHeight <== this.height
  cont.prefWidth <== this.width
}

object Sgar extends JFXApp3 {

  val props = new java.util.Properties()
  var mainScene: MainScene = _

  val logfile: File = Helpers.createTempFile("sgar",".txt")
  private val logps: FileOutputStream = new FileOutputStream(logfile)

  System.setProperty("mail.mime.parameters.strict", "false") // allow whitespace in attachment names without quote

  lazy val logView: TextArea = new TextArea {
    text = "============= Application log ==================== \n"
  }

  // JFXApp3: UI init stuff must go into this!
  override def start(): Unit = {

    // redirect console output, must happen on top of this object!
    val oldOut: PrintStream = System.out
    val oldErr: PrintStream = System.err
    System.setOut(new PrintStream(new MyConsole(false), true))
    System.setErr(new PrintStream(new MyConsole(true), true))

    class MyConsole(errchan: Boolean) extends java.io.OutputStream {
      override def write(b: Int): Unit = {
        Helpers.runUI {
          if (mainScene != null) logView.appendText(b.toChar.toString)
        }
        if (logps != null) logps.write(b)
        (if (errchan) oldErr else oldOut).print(b.toChar.toString)
      }
    }

    // Dock icon (do this here, otherwise stopApp() doesn't work)
    if (Helpers.isMac) Taskbar.getTaskbar.setIconImage(ImageIO.read(getClass.getResource("/icon-128.png")))

    stage = new JFXApp3.PrimaryStage {
      title.value = "Gmail Attachment Remover"
      width = 1000
      height = 750
      mainScene = new MainScene(this, props)
      scene = mainScene
    }

    // init
    mainScene.setButtons(flist = true, getemails = true)
    mainScene.updateAccountsGuiFromProperties(props)
  }

  private def getSettingsFile: File = {
    val fp: String = if (Helpers.isMac) {
      System.getProperty("user.home") + "/Library/Application Support/Sgar"
    } else if (Helpers.isLinux) {
      System.getProperty("user.home") + "/.sgar"
    } else if (Helpers.isWin) {
      System.getenv("APPDATA") + File.separator +  "Sgar"
    } else throw new Exception("operating system not found")
    new File(fp + File.separator + "sgarsettings.txt")
  }

  def loadSettings(): Unit = {
    val ff = getSettingsFile
    println("load config: settings file = " + ff.getPath)
    if (ff.exists()) {
      val fin = new FileInputStream(ff)
      props.load(fin)
      fin.close()
    }
  }

  private def saveSettings(): Unit = {
    val ff = getSettingsFile
    println("save config: settings file = " + ff.getPath)
    if (!ff.getParentFile.exists) ff.getParentFile.mkdirs()
    val fos = new FileOutputStream(ff)
    props.store(fos,null)
    fos.close()
  }

  // TODO why not called?
  override def stopApp(): Unit = {
    println("*** stopapp")
    GmailStuff.cleanup()
    mainScene.updatePropertiesForAccount(props)
    mainScene.updateAccountsPropertiesFromGui(props)
    saveSettings()
    Helpers.caffeinate(false)
    if (logps != null) logps.close()
    super.stopApp()
    System.exit(0)
  }

  sys.addShutdownHook({
    Helpers.caffeinate(false)
  })

}


object Helpers {
  def isMac: Boolean = System.getProperty("os.name").toLowerCase.contains("mac")
  def isLinux: Boolean = System.getProperty("os.name").toLowerCase.matches("(.*nix)|(.*nux)")
  def isWin: Boolean = System.getProperty("os.name").toLowerCase.contains("win")
  def createTempFile(prefix: String, suffix: String): File = { // standard io.File.createTempFile points often to strange location
  val tag = System.currentTimeMillis().toString
    var dir = System.getProperty("java.io.tmpdir")
    if (Helpers.isLinux || Helpers.isMac) if (new File("/tmp").isDirectory)
      dir = "/tmp"
    new File(dir + "/" + prefix + "-" + tag + suffix)
  }
  def runUI( f: => Unit ): Unit = {
    if (!scalafx.application.Platform.isFxApplicationThread) {
      scalafx.application.Platform.runLater( () => {
        f
      })
    } else { f }
  }

  private var caffProc: Process = _
  def caffeinate(doit: Boolean): Unit = {
    if (Sgar.mainScene.cbCaffeinate.selected.getValue) {
      if (isMac) {
        println(s"caffeinate ($doit)...")
        if (doit) {
          if (caffProc == null) {
            val pb = new ProcessBuilder(List(/*"/usr/bin/bash", "-c", "ls"*/ "/usr/bin/caffeinate").asJava).inheritIO()
            caffProc = pb.start()
          }
        } else {
          if (caffProc != null) caffProc.destroyForcibly()
          caffProc = null
        }
      }
    }
  }

  // https://github.com/scalafx/ProScalaFX/blob/master/src/proscalafx/ch06/ServiceExample.scala
  class MyWorker[T](atitle: String, atask: javafx.concurrent.Task[T]) {
    private object worker extends Service[T](new javafx.concurrent.Service[T]() {
      override def createTask(): javafx.concurrent.Task[T] = atask
    })
    val lab = new Label("")
    private val progress: ProgressBar = new ProgressBar { minWidth = 500 }
    private val al: Dialog[Unit] = new Dialog[Unit] {
      initOwner(Sgar.stage)
      title = atitle
      dialogPane.value.content = new VBox { children ++= Seq(lab, progress) }
      dialogPane.value.getButtonTypes += ButtonType.Cancel
    }
    al.onCloseRequest = () => {
      atask.cancel()
    }
    def runInBackground(): Unit = {
      al.show()
      lab.text <== worker.message
      progress.progress <== worker.progress
      worker.onSucceeded = (_: WorkerStateEvent) => {
        al.close()
      }
      worker.onFailed = (_: WorkerStateEvent) => {
        println("onfailed: " + atask.getException.getMessage)
        atask.getException.printStackTrace()
        al.close()
      }
      worker.start()
    }
  }

  import java.net.URISyntaxException

  // https://stackoverflow.com/a/22404140
  def getClassBuildTime: Date = {
    var d: Date = null
    val currentClass = new Object() {}.getClass.getEnclosingClass
    val resource = currentClass.getResource(currentClass.getSimpleName + ".class")
    if (resource != null) {
      if (resource.getProtocol.equals("file")) {
        try {
          d = new Date(new File(resource.toURI).lastModified)
        } catch {
          case _: URISyntaxException =>
        }
      } else if (resource.getProtocol.equals("jar")) {
        val path = resource.getPath
        d = new Date(new File(path.substring(5, path.indexOf("!"))).lastModified)
      } else if (resource.getProtocol.equals("zip")) {
        val path = resource.getPath
        val jarFileOnDisk = new File(path.substring(0, path.indexOf("!")))
        //long jfodLastModifiedLong = jarFileOnDisk.lastModified ();
        //Date jfodLasModifiedDate = new Date(jfodLastModifiedLong);

        try {
          val jf = new JarFile(jarFileOnDisk)
          val ze = jf.getEntry(path.substring(path.indexOf("!") + 2)) //Skip the ! and the /
          val zeTimeLong = ze.getTime
          val zeTimeDate = new Date(zeTimeLong)
          d = zeTimeDate
        } catch {
          case _: IOException =>
          case _: RuntimeException =>
        }
      }
    }
    d
  }

}
