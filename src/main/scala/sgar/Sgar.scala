
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

import sgar.GmailStuff.{Bodypart, ToDelete}

import scala.collection.mutable.ListBuffer
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scalafx.Includes._
import scalafx.application.JFXApp
import scalafx.beans.property.ReadOnlyStringWrapper
import scalafx.collections.ObservableBuffer
import scalafx.event.ActionEvent
import scalafx.geometry.{Insets, Pos}
import scalafx.scene.Scene
import scalafx.scene.control._
import scalafx.scene.layout._
import scalafx.stage.DirectoryChooser
import scalafx.scene.control.Alert.AlertType
import HBox._
import Button._
import TreeTableColumn._
import java.awt.Desktop
import java.io.{File, FileInputStream, FileOutputStream, PrintStream}
import java.net.URI

object Sgar extends JFXApp {

  val props = new java.util.Properties()

  val home = "https://bitbucket.org/wolfgang/gmail-attachment-remover"
  val space = 4.0
  var currentAccount: String = _

  // redirect console output, must happen on top of this object!
  val oldOut = System.out
  val oldErr = System.err
  var logps: FileOutputStream = _
  System.setOut(new PrintStream(new MyConsole(false), true))
  System.setErr(new PrintStream(new MyConsole(true), true))

  val logfile = Helpers.createTempFile("sgar",".txt")
  logps = new FileOutputStream(logfile)

  class MyConsole(errchan: Boolean) extends java.io.OutputStream {
    override def write(b: Int): Unit = {
      Helpers.runUI { logView.appendText(b.toChar.toString) }
      if (logps != null) logps.write(b)
      (if (errchan) oldErr else oldOut).print(b.toChar.toString)
    }
  }

  val logView = new TextArea {
    text = "============= Application log ==================== \n"
  }

  def getSettingsFile: File = {
    val fp: String = if (Helpers.isMac) {
      System.getProperty("user.home") + "/Library/Application Support/Sgar"
    } else if (Helpers.isLinux) {
      System.getProperty("user.home") + "/.sgar"
    } else if (Helpers.isWin) {
      System.getenv("APPDATA") + File.separator +  "Sgar"
    } else throw new Exception("operating system not found")
    new File(fp + File.separator + "sgarsettings.txt")
  }

  def loadSettings() {
    val ff = getSettingsFile
    println("load config: settings file = " + ff.getPath)
    if (ff.exists()) {
      val fin = new FileInputStream(ff)
      props.load(fin)
      fin.close()
    }
  }

  def saveSettings() {
    val ff = getSettingsFile
    println("save config: settings file = " + ff.getPath)
    if (!ff.getParentFile.exists) ff.getParentFile.mkdirs()
    val fos = new FileOutputStream(ff)
    props.store(fos,null)
    fos.close()
  }

  def updateAccountsGuiFromProperties(): Unit = {
    val accprop = props.getProperty("accounts", "")
    if (cbaccount.items != null) {
      cbaccount.items.get.clear()
      if (accprop != "") for (a <- accprop.split(",,,")) { cbaccount.items.get += a }
      cbaccount.getSelectionModel.selectFirst()
    }
  }

  def updateAccountsPropertiesFromGui(): Unit = {
    props.put("accounts", cbaccount.items.get.mkString(",,,"))
  }

  def updatePropertiesForAccount(): Unit = {
    if (currentAccount != null) {
      props.put(currentAccount + "-backupdir", tfbackupdir.text.value)
      props.put(currentAccount + "-minbytes", tfminbytes.text.value)
      props.put(currentAccount + "-limit", tflimit.text.value)
      props.put(currentAccount + "-label", tflabel.text.value)
      props.put(currentAccount + "-gmailsearch", cbgmailsearch.getValue)
    }
  }

  def updateGuiForAccount(): Unit = {
    currentAccount = cbaccount.getValue
    if (currentAccount != null) {
      tfbackupdir.text = props.getProperty(currentAccount + "-backupdir", "???")
      tfminbytes.text = props.getProperty(currentAccount + "-minbytes", "10000")
      tflimit.text = props.getProperty(currentAccount + "-limit", "10")
      tflabel.text = props.getProperty(currentAccount + "-label", "removeattachments")
      cbgmailsearch.setValue(props.getProperty(currentAccount + "-gmailsearch", "???")) // don't use value = !
    }
  }


  class TreeThing(val toDelete: ToDelete, val bodypart: Bodypart) {
    def getColumn(i: Int): String = {
      if (bodypart == null) {
        List(toDelete.gmid.toString, toDelete.subject, toDelete.date, toDelete.from)(i)
      } else {
        List(bodypart.bpi.toString, bodypart.filename, bodypart.filesize.toString, bodypart.contentType)(i)
      }
    }
  }

  val tiroot = new TreeItem[TreeThing](new TreeThing(null, null))
  tiroot.setExpanded(true)

  val ttv = new TreeTableView[TreeThing] {
    columnResizePolicy = TreeTableView.ConstrainedResizePolicy
    val colheaders = List("Gmail ID/Attachment #", "Subject/Filename", "Date/Size", "Sender/Type")
    for (i <- colheaders.indices) {
      columns += new TreeTableColumn[TreeThing, String](colheaders(i)) {
        cellValueFactory = { p => ReadOnlyStringWrapper(p.value.getValue.getColumn(i)) }
      }
    }
    selectionModel.value.selectionMode = SelectionMode.Multiple
    root = tiroot
    showRoot = false
  }

  val btRemoveRows = new Button("Remove selected rows") {
    tooltip = new Tooltip { text = "... to prevent attachments from being removed"}
    onAction = (_: ActionEvent) => {
      tiroot.getChildren.removeAll(ttv.getSelectionModel.getSelectedItems)
//      for (cells <- ttv.getSelectionModel.getSelectedItems) {
//        println(s"remove cell: " + cells)
//        cells.getTreeItem.getParent.children -= cells.getTreeItem
//      }
      //      for (cells <- ttv.getSelectionModel.getSelectedCells) {
//        println(s"remove cell: " + cells)
//        cells.getTreeItem.getParent.children -= cells.getTreeItem
//      }
    }
  }

  val listPane = new VBox(space) {
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

  val cbaccount = new ChoiceBox[String] {
    hgrow = Priority.Always
    maxWidth = Double.MaxValue
    tooltip = new Tooltip { text = "Gmail email adress" }
    items = new ObservableBuffer[String]()
    value.onChange {
      updatePropertiesForAccount() // uses old account
      updateGuiForAccount()
    }
  }


  loadSettings()
  println("Logging to file " + logfile.getPath)


  val tfbackupdir = new TextField {
    hgrow = Priority.Always
    maxWidth = Double.MaxValue
    text = ""
    tooltip = new Tooltip { text = "Emails are saved here before attachments are removed" }
  }
  val tfminbytes = new TextField { text =  "" ; tooltip = new Tooltip { text = "Minimum size of attachments for being considered for removal" } }
  val tflimit = new TextField { text = "" ; tooltip = new Tooltip { text = "Maximum number of emails to be considered in a single run" } }
  val tflabel = new TextField {
    hgrow = Priority.Always
    maxWidth = Double.MaxValue
    text = ""
    tooltip = new Tooltip { text = "Only consider emails having this label; consider all if empty" }
  }
  val cbgmailsearch = new ComboBox[String] {
    hgrow = Priority.Always
    maxWidth = Double.MaxValue
    tooltip = new Tooltip { text = "mind that ' label:<label>' is appended!" }
    val strings = ObservableBuffer("size:1MB has:attachment -in:inbox", "size:1MB has:attachment older_than:12m -in:inbox")
    items = strings
    editable = true
  }

  val bSelectbackupdir = new Button("Select...") {
    onAction = (_: ActionEvent) => {
      val fcbackupdir = new DirectoryChooser {
        title = "Pick backup folder..."
      }
      tfbackupdir.text = fcbackupdir.showDialog(stage).getAbsolutePath
    }
  }

  def setButtons(flist: Boolean = false, getemails: Boolean = false, rmattach: Boolean = false) {
    Helpers.runUI {
      btFolderList.disable = !flist
      btGetEmails.disable = !getemails
      btRemoveAttachments.disable = !rmattach
      btRemoveRows.disable = !(getemails && rmattach)
    }
  }

  def setupGmail(): Unit = {
    updatePropertiesForAccount()
    GmailStuff.backupdir = new File(tfbackupdir.text.value)
    GmailStuff.username = cbaccount.getValue
    GmailStuff.label = tflabel.text.value
    GmailStuff.gmailsearch = cbgmailsearch.getValue
    GmailStuff.minbytes = tfminbytes.text.value.toInt
    GmailStuff.limit = tflimit.text.value.toInt
    GmailStuff.refreshtoken = props.getProperty(cbaccount.getValue + "-token")
  }

  val btFolderList = new Button("Get gmail folder list") {
    tooltip = new Tooltip { text = "Mainly for debug purposes. Output is shown in log."}
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

  val btAbout = new Button("About") {
    tooltip = new Tooltip { text = s"Open website $home"}
    onAction = (_: ActionEvent) => {
      println(s"Open $home ...")
      if (Desktop.isDesktopSupported) {
        val desktop = Desktop.getDesktop
        if (desktop.isSupported(Desktop.Action.BROWSE)) {
          desktop.browse(new URI(home))
        }
      }
    }
  }

  def showGmailErrorCleanup(e: Throwable): Unit = {
    println("Exception " + e.getMessage)
    e.printStackTrace()
    Helpers.runUI {
      new Alert(AlertType.Error, "Error communicating with gmail.\nDid you authenticate?\nError: " + e.getMessage).showAndWait()
    }
  }

  val btGetEmails = new Button("Find emails") {
    tooltip = new Tooltip { text = "Find emails based on selected criteria"}
    onAction = (_: ActionEvent) => {
      setButtons()
      setupGmail()
      tiroot.children.clear()
      Future {
        var dellist = new ListBuffer[ToDelete]()
        try {
          dellist = GmailStuff.getToDelete
          Helpers.runUI {
            for (todel <- dellist) {
              val ti = new TreeItem[TreeThing](new TreeThing(todel, null))
              for (bp <- todel.bodyparts) {
                ti.children += new TreeItem[TreeThing](new TreeThing(todel, bp))
              }
              tiroot.children += ti
            }
          }
        } catch {
          case e: Exception => showGmailErrorCleanup(e)
        } finally {
          setButtons(flist = true, getemails = true, rmattach = dellist.nonEmpty)
        }
      }
    }
  }

  val btRemoveAttachments = new Button("Remove attachments") {
    tooltip = new Tooltip { text = "Remove all attachments shown in the table below"}
    onAction = (_: ActionEvent) => {
      setButtons()
      setupGmail()
      val dellist = new ListBuffer[ToDelete]
      for (timails <- tiroot.children) {
        val bplist = new ListBuffer[Bodypart]
        for (tibps <- timails.getChildren) {
          bplist += tibps.getValue.bodypart
        }
        if (bplist.nonEmpty) {
          var td = timails.getValue.toDelete
          td.bodyparts.clear()
          td.bodyparts ++= bplist
          dellist += td
        }
      }
      for (todel <- dellist) println(todel.toString)
      Future {
        try {
          GmailStuff.doDelete(dellist)
          Helpers.runUI { tiroot.children.clear() }
        } catch {
          case e: Exception => showGmailErrorCleanup(e)
        } finally {
          setButtons(flist = true, getemails = true)
        }
      }
    }
  }

  val btAuthGoogle = new Button("Authenticate account") {
    tooltip = new Tooltip { text = "This needs to be run only once, or if there are authentication problems!"}
    onAction = (_: ActionEvent) => {
      try {
        var doit = 1 // 1-webserver, 2-manual, -1-done1 -2-done2
        var authcode = ""
        do {
          val weblink = OAuth2google.GeneratePermissionUrl(GmailStuff.clientid, doit == 1)
          if (Desktop.isDesktopSupported) {
            val desktop = Desktop.getDesktop
            if (desktop.isSupported(Desktop.Action.BROWSE)) {
              new Alert(AlertType.Information, "A browser window opens. Follow the instructions" + (if (doit == 2) "and copy to code to clipbaord!" else "!")).showAndWait()
              desktop.browse(new URI(weblink))
            } else {
              new Alert(AlertType.Information, "Please open the URL shown in the log in a browser. Follow the instructions" + (if (doit == 2) "and copy to code to clipbaord!" else "!")).showAndWait()
              println("please open this URL in your browser:\n" + weblink)
            }
          }

          doit = doit match {
            case 1 =>
              val res = OAuth2google.catchResponse()
              if (res != null) {
                authcode = res
                println("Received an auth token...")
                -1
              } else {
                println("Automatic authentication failed, trying again manually...")
                2
              }
            case 2 =>
              val dialog = new TextInputDialog()
              dialog.setTitle("Google OAuth2")
              dialog.setHeaderText("Google OAuth2 response")
              dialog.setContentText("Enter Google OAuth2 response:")
              val res = dialog.showAndWait()
              if (res.nonEmpty) {
                authcode = res.get
              }
              -2
          }
        } while (doit > 0)
        println("Obtaining refresh token...")
        val (_, refreshtoken) = OAuth2google.AuthorizeTokens(GmailStuff.clientid, GmailStuff.clientsecret, authcode, doit == -1)
        props.put(cbaccount.getValue + "-token", refreshtoken)
        println("Authentication succeeded!")
      } catch {
        case e: Exception => showGmailErrorCleanup(e)
      }
    }
  }

  val settingsPane = new VBox(space) {
    children ++= List(
      new HBox(space) {
        alignment = Pos.CenterLeft
        children ++= List(
          new Label("Gmail Account: "),
          cbaccount,
          btAuthGoogle,
          new Button("Add") {
            tooltip = new Tooltip { text = "Add a new Gmail account"}
            onAction = (_: ActionEvent) => {
              val dialog = new TextInputDialog()
              dialog.setTitle("New account")
              dialog.setHeaderText("Enter gmail address")
              val res = dialog.showAndWait()
              res.foreach( r => {
                cbaccount.getItems.add(r)
                cbaccount.setValue(r)
              })
            }
          },
          new Button("Remove") {
            tooltip = new Tooltip { text = "Remove the selected Gmail account from the list"}
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
      new HBox(space) { alignment = Pos.CenterLeft ; children ++= List( new Label("Backupfolder: "), tfbackupdir, bSelectbackupdir ) },
      new HBox(space) { alignment = Pos.CenterLeft ; children ++= List( new Label("Gmail label: "), tflabel ) },
      new HBox(space) { alignment = Pos.CenterLeft ; children ++= List( new Label("Gmail search: "), cbgmailsearch ) },
      new HBox(space) { alignment = Pos.CenterLeft ; children ++= List(
        new Label("Minimum Attachment size (bytes): "),
        tfminbytes,
        new Label("     Limit no. messages:"),
        tflimit
      ) },
      new HBox(space) {
        alignment = Pos.Center
        children += new Button("Quit") {
          onAction = (_: ActionEvent) => {
            stopApp()
          }
        }
        children ++= List(btGetEmails, btRemoveAttachments, btFolderList, btAbout)
      }
    )
  }

  val cont = new VBox(space) {
    children ++= List(settingsPane, listPane, logView)
    padding = Insets(2*space)
  }

  stage = new JFXApp.PrimaryStage {
    title.value = "Gmail Attachment Remover"
    width = 1000
    height = 750
    scene = new Scene {
      root = cont
    }
  }

  override def stopApp(): Unit = {
    GmailStuff.cleanup()
    updatePropertiesForAccount()
    updateAccountsPropertiesFromGui()
    saveSettings()
    if (logps != null) logps.close()
    super.stopApp()
    System.exit(0)
  }

  // init
  setButtons(flist = true, getemails = true)
  updateAccountsGuiFromProperties()

}


object Helpers {
  def isMac: Boolean = System.getProperty("os.name").toLowerCase.contains("mac")
  def isLinux: Boolean = System.getProperty("os.name").toLowerCase.contains("nix")
  def isWin: Boolean = System.getProperty("os.name").toLowerCase.contains("win")
  def createTempFile(prefix: String, suffix: String): File = { // standard io.File.createTempFile points often to strange location
  val tag = System.currentTimeMillis().toString
    var dir = System.getProperty("java.io.tmpdir")
    if (Helpers.isLinux || Helpers.isMac) if (new File("/tmp").isDirectory)
      dir = "/tmp"
    new File(dir + "/" + prefix + "-" + tag + suffix)
  }
  def runUI( f: => Unit ) {
    if (!scalafx.application.Platform.isFxApplicationThread) {
      scalafx.application.Platform.runLater( new Runnable() {
        def run() { f }
      })
    } else { f }
  }
}