package sgar

import javafx.scene.control.Alert.AlertType

import sgar.GmailStuff.{Bodypart, ToDelete}

import scala.collection.mutable.ListBuffer
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

import scalafx.application.JFXApp
import scalafx.Includes._
import scalafx.beans.property.StringProperty
import scalafx.collections.ObservableBuffer
import scalafx.event.ActionEvent
import scalafx.geometry.Pos
import scalafx.scene.Scene
import scalafx.scene.control._
import scalafx.scene.layout.{HBox, VBox}
import scalafx.stage.DirectoryChooser
import HBox._
import Button._

import java.awt.Desktop
import java.io._
import java.net.URI

import javafx.beans.value.ObservableValue
import javafx.scene.control.TreeTableColumn.CellDataFeatures
import javafx.scene.control.{Alert, TextInputDialog, TreeTableColumn, TreeTableView}
import javafx.util.Callback


object Sgar extends JFXApp {

  val props = new java.util.Properties()

  var currentAccount: String = null

  // redirect console output, must happen on top of this object!
  val oldOut = System.out
  val oldErr = System.err
  var logps: FileOutputStream = null
  System.setOut(new PrintStream(new MyConsole(false), true))
  System.setErr(new PrintStream(new MyConsole(true), true))

  val logfile = File.createTempFile("sgar",".txt")
  logps = new FileOutputStream(logfile)

  class MyConsole(errchan: Boolean) extends java.io.OutputStream {
    override def write(b: Int): Unit = {
      runUI { logView.appendText(b.toChar.toString) }
      if (logps != null) logps.write(b)
      (if (errchan) oldErr else oldOut).print(b.toChar.toString)
    }
  }

  def runUI( f: => Unit ) {
    if (!scalafx.application.Platform.isFxApplicationThread) {
      scalafx.application.Platform.runLater( new Runnable() {
        def run() { f }
      })
    } else { f }
  }

  val logView = new TextArea {
    text = "============= Application log ==================== \n"
  }

  def getSettingsFile = {
    val mac = "(.*mac.*)".r
    val win = "(.*win.*)".r
    val nix = "(.*nix.*)".r
    val fp: String = System.getProperty("os.name").toLowerCase match {
      case mac(_) => System.getProperty("user.home") + "/Library/Application Support/Sgar"
      case win(_) => System.getenv("APPDATA") + File.separator +  "Sgar"
      case nix(_) => System.getProperty("user.home") + "/.sgar"
      case _ => throw new Exception("operating system not known")
    }
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
    if (tfaccount.items != null) {
      tfaccount.items.get.clear()
      if (accprop != "") for (a <- accprop.split(",,,")) { tfaccount.items.get += a }
      tfaccount.getSelectionModel.selectFirst()
    }
  }

  def updateAccountsPropertiesFromGui() = {
    props.put("accounts", tfaccount.items.get.mkString(",,,"))
  }

  def updatePropertiesForAccount(): Unit = {
    if (currentAccount != null) {
      props.put(currentAccount + "-backupdir", tfbackupdir.text.value)
      props.put(currentAccount + "-minbytes", tfminbytes.text.value)
      props.put(currentAccount + "-limit", tflimit.text.value)
      props.put(currentAccount + "-label", tflabel.text.value)
      props.put(currentAccount + "-gmailsearch", tfgmailsearch.getValue)
    }
  }

  def updateGuiForAccount(): Unit = {
    currentAccount = tfaccount.getValue
    if (currentAccount != null) {
      tfbackupdir.text = props.getProperty(currentAccount + "-backupdir", "???")
      tfminbytes.text = props.getProperty(currentAccount + "-minbytes", "10000")
      tflimit.text = props.getProperty(currentAccount + "-limit", "10")
      tflabel.text = props.getProperty(currentAccount + "-label", "removeattachments")
      tfgmailsearch.setValue(props.getProperty(currentAccount + "-gmailsearch", "???")) // don't use value = !
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
    val colwidths = List(200.0, 200.0, 250.0, 200.0)
    for (i <- 0 to colwidths.length - 1) {
      val ttc1 = new TreeTableColumn[TreeThing, String] {
        setPrefWidth(colwidths(i))
        setCellValueFactory( new Callback[TreeTableColumn.CellDataFeatures[TreeThing, String], ObservableValue[String]] {
          override def call(param: CellDataFeatures[TreeThing, String]): ObservableValue[String] =
            new StringProperty(param.getValue.getValue.getColumn(i))
        })
      }
      getColumns.add(ttc1)
    }

    setRoot(tiroot)
    setShowRoot(false)
  }

  val listPane = new VBox {
    fillWidth = true
    content ++= Seq(
      ttv,
      new HBox {
        content ++= Seq(
          new Button("remove selected rows") {
            onAction = (ae: ActionEvent) => {
              for (idx <- ttv.getSelectionModel.getSelectedCells) {
                idx.getTreeItem.getParent.children -= idx.getTreeItem
              }
            }
          }
        )
      }
    )
  }

  val tfaccount = new ChoiceBox[String] {
    prefWidth = 300
    tooltip = new Tooltip { text = "Gmail adress" }
    items = new ObservableBuffer[String]()
    value.onChange {
      updatePropertiesForAccount() // uses old account
      updateGuiForAccount()
    }
  }


  loadSettings()
  println("Logging to file " + logfile.getPath)


  val tfbackupdir = new TextField { prefWidth = 500; text = "" }
  val tfminbytes = new TextField { text =  "" }
  val tflimit = new TextField { text = "" }
  val tflabel = new TextField { prefWidth = 500; text = "" }
  val tfgmailsearch = new ComboBox[String] {
    prefWidth = 500
    tooltip = new Tooltip { text = "mind that ' label:<label>' is appended!" }
    val strings = ObservableBuffer("size:1MB has:attachment -in:inbox", "size:1MB has:attachment older_than:12m -in:inbox")
    items = strings
    editable = true
  }

  val bSelectbackupdir = new Button("Select...") {
    onAction = (ae: ActionEvent) => {
      val fcbackupdir = new DirectoryChooser {
        title = "Pick backup folder..."
      }
      tfbackupdir.text = fcbackupdir.showDialog(stage).getAbsolutePath
    }
  }

  def setButtons(flist: Boolean = false, getemails: Boolean = false, rmattach: Boolean = false) {
    runUI {
      btFolderList.disable = !flist
      btGetEmails.disable = !getemails
      btRemoveAttachments.disable = !rmattach
    }
  }

  def setupGmail(): Unit = {
    updatePropertiesForAccount()
    GmailStuff.backupdir = new File(tfbackupdir.text.value)
    GmailStuff.username = tfaccount.getValue
    GmailStuff.label = tflabel.text.value
    GmailStuff.gmailsearch = tfgmailsearch.getValue
    GmailStuff.minbytes = tfminbytes.text.value.toInt
    GmailStuff.limit = tflimit.text.value.toInt
    GmailStuff.refreshtoken = props.getProperty(tfaccount.getValue + "-token")
  }

  val btFolderList = new Button("Get gmail folder list") {
    onAction = (ae: ActionEvent) => {
      setButtons()
      setupGmail()
      Future {
        GmailStuff.getAllFolders
        setButtons(flist = true, getemails = true, rmattach = false)
      }
    }
  }

  val btGetEmails = new Button("Find emails") {
    onAction = (ae: ActionEvent) => {
      setButtons()
      setupGmail()
      tiroot.children.clear()
      Future {
        val dellist = GmailStuff.getToDelete
        runUI {
          for (todel <- dellist) {
            val ti = new TreeItem[TreeThing](new TreeThing(todel, null))
            for (bp <- todel.bodyparts) {
              ti.children += new TreeItem[TreeThing](new TreeThing(todel, bp))
            }
            tiroot.children += ti
          }
        }
        setButtons(flist = true, getemails = true, rmattach = dellist.nonEmpty)
      }
    }
  }

  val btRemoveAttachments = new Button("Remove attachments") {
    onAction = (ae: ActionEvent) => {
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
        GmailStuff.doDelete(dellist)
        runUI { tiroot.children.clear() }
        setButtons(flist = true, getemails = true, rmattach = false)
      }
    }
  }

  val btAuthGoogle = new Button("Authenticate account") {
    onAction = (ae: ActionEvent) => {
      try {
        val weblink = OAuth2google.GeneratePermissionUrl(GmailStuff.clientid)
        if (Desktop.isDesktopSupported) {
          val desktop = Desktop.getDesktop
          if (desktop.isSupported(Desktop.Action.BROWSE)) {
            new Alert(AlertType.INFORMATION, "A browser window opens. Follow the instructions and copy to code to clipbaord!").showAndWait()
            desktop.browse(new URI(weblink))
          } else {
            new Alert(AlertType.INFORMATION, "Please open the URL shown in the log in a browser, follow the intructions and copy the code to clipboard!").showAndWait()
            println("please open this URL in your browser:\n" + weblink)
          }
        }
        val dialog = new TextInputDialog()
        dialog.setTitle("Google OAuth2")
        dialog.setHeaderText("Google OAuth2 response")
        dialog.setContentText("Enter Google OAuth2 response:")
        val res = dialog.showAndWait()
        if (res.isPresent) {
          val authcode = res.get()
          val (_, refreshtoken) = OAuth2google.AuthorizeTokens(GmailStuff.clientid, GmailStuff.clientsecret, authcode)
          props.put(tfaccount.getValue + "-token", refreshtoken)
          println("Authentification succeeded!")
        }
      } catch {
        case e: Exception => e.printStackTrace()
      }
    }
  }

  val settingsPane = new VBox(2.0) {
    fillWidth = true
    content ++= List(
      new HBox {
        alignment = Pos.CenterLeft
        content ++= List(
          new Label("Gmail Account:"),
          tfaccount,
          btAuthGoogle,
          new Button("Add") {
            onAction = (ae: ActionEvent) => {
              val dialog = new TextInputDialog()
              dialog.setTitle("New account")
              dialog.setHeaderText("Enter gmail address")
              val res = dialog.showAndWait()
              if (res.isPresent) {
                val newemail = res.get()
                tfaccount.items.get += newemail
                tfaccount.setValue(newemail)
              }
            }
          },
          new Button("Remove") {
            onAction = (ae: ActionEvent) => {
              val iter = props.entrySet.iterator
              while (iter.hasNext) {
                val entry = iter.next()
                if (entry.getKey.asInstanceOf[String].startsWith(currentAccount + "-")) iter.remove()
              }
              currentAccount = null // prevent from saving deleted account to properties
              tfaccount.items.get -= tfaccount.getValue
              if (tfaccount.items.get.nonEmpty) tfaccount.getSelectionModel.selectFirst()
            }
          }
        )
      },
      new HBox { alignment = Pos.CenterLeft ; content ++= List( new Label("Backupfolder:"), tfbackupdir, bSelectbackupdir ) },
      new HBox { alignment = Pos.CenterLeft ; content ++= List( new Label("Gmail label:"), tflabel ) },
      new HBox { alignment = Pos.CenterLeft ; content ++= List( new Label("Gmail search:"), tfgmailsearch ) },
      new HBox { alignment = Pos.CenterLeft ; content ++= List(
        new Label("Minimum Attachment size (bytes):"),
        tfminbytes,
        new Label("     Limit no. messages:"),
        tflimit
      ) },
      new HBox {
        alignment = Pos.Center
        content += new Button("Quit") {
          onAction = (ae: ActionEvent) => {
            stopApp()
          }
        }
        content ++= List(btGetEmails, btRemoveAttachments, btFolderList)
//        content += new Button("Test") {
//          onAction = (ae: ActionEvent) => {
//            setupGmail()
//            Future {
//              GmailStuff.doTest()
//            }
//          }
//        }
      }
    )
  }

  val cont = new VBox {
    fillWidth = true
    content ++= Seq(
      settingsPane,
      listPane,
      logView
    )
  }

  stage = new JFXApp.PrimaryStage {
    title.value = "Gmail Attachment Remover"
    width = 1000
    height = 750
    scene = new Scene {
      content = cont
    }
    cont.prefWidth <== scene.width
    cont.prefHeight <== scene.height
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

  // init things

    setButtons(flist = true, getemails = true, rmattach = false)

    updateAccountsGuiFromProperties()


}
