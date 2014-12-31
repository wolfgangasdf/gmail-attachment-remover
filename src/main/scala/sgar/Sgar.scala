package sgar

import java.io.{PrintStream, FileOutputStream, FileInputStream, File}

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

import javafx.beans.value.ObservableValue
import javafx.scene.control.TreeTableColumn.CellDataFeatures
import javafx.scene.control.{TreeTableColumn, TreeTableView}
import javafx.util.Callback

/*
TODO:
test windows, if backup folder on other drive!
support oauth2: https://java.net/projects/javamail/pages/OAuth2
 */

object Sgar extends JFXApp {

  val props = new java.util.Properties()

  // redirect console output, must happen on top of this object!
  val oldOut = System.out
  val oldErr = System.err
  System.setOut(new PrintStream(new MyConsole(false), true))
  System.setErr(new PrintStream(new MyConsole(false), true))

  def runUI( f: => Unit ) {
    if (!scalafx.application.Platform.isFxApplicationThread) {
      scalafx.application.Platform.runLater( new Runnable() {
        def run() { f }
      })
    } else { f }
  }

  class MyConsole(errchan: Boolean) extends java.io.OutputStream {
    override def write(b: Int): Unit = {
      runUI { logView.appendText(b.toChar.toString) }
      (if (errchan) oldErr else oldOut).print(b.toChar.toString)
    }
  }

  val logView = new TextArea {
    text = "============= Application log ==================== \n"
  }

  def getSettingsFile = {
    val fp = new File(".").getAbsoluteFile.getParentFile // gets CWD!
    new File(fp.getPath + File.separator + "sgarsettings.txt")
  }
  def loadSettings() {
    val ff = getSettingsFile
    println("load config: settings file = " + ff.getPath)
    if (!ff.exists()) ff.createNewFile()
    props.load(new FileInputStream(ff))
  }
  def saveSettings() {
    val ff = getSettingsFile
    println("save config: settings file = " + ff.getPath)
    props.put("username",tfuser.text.value)
    props.put("password", tfpass.text.value)
    props.put("backupdir", tfbackupdir.text.value)
    props.put("minbytes", tfminbytes.text.value)
    props.put("limit", tflimit.text.value)
    props.put("label", tflabel.text.value)
    props.put("gmailsearch", tfgmailsearch.selectionModel.value.getSelectedItem)
    val fos = new FileOutputStream(ff)
    props.store(fos,null)
    fos.close()
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

  // TODO write wrappers for scalafx
  val ttv = new TreeTableView[TreeThing] {
    val colwidths = List(150.0, 200.0, 100.0, 200.0)
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

  loadSettings()

  val tfuser = new TextField { prefWidth = 500; text = props.getProperty("username","???") }
  val tfpass = new TextField { prefWidth = 500; text = props.getProperty("password","???") }
  val tfbackupdir = new TextField { prefWidth = 500; text = props.getProperty("backupdir","???") }
  val tfminbytes = new TextField { text = props.getProperty("minbytes","10000") }
  val tflimit = new TextField { text = props.getProperty("limit","10") }
  val tflabel = new TextField { prefWidth = 500; text = props.getProperty("label","removeattachments") }
  val tfgmailsearch = new ComboBox[String] {
    prefWidth = 500
    tooltip = new Tooltip { text = "mind that ' label:<label>' is appended!" }
    val strings = ObservableBuffer("size:10KB has:attachment", "size:10KB has:attachment older_than:5m")
    items = strings
    value = props.getProperty("gmailsearch","???")
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
    GmailStuff.backupdir = new File(tfbackupdir.text.value)
    GmailStuff.username = tfuser.text.value
    GmailStuff.password = tfpass.text.value
    GmailStuff.label = tflabel.text.value
    GmailStuff.gmailsearch = tfgmailsearch.selectionModel.value.getSelectedItem
    GmailStuff.minbytes = tfminbytes.text.value.toInt
    GmailStuff.limit = tflimit.text.value.toInt
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

  val settingsPane = new VBox(2.0) {
    fillWidth = true
    content ++= List(
      new HBox { alignment = Pos.CenterLeft ; content ++= List( new Label("Email:"), tfuser ) },
      new HBox { alignment = Pos.CenterLeft ; content ++= List( new Label("Password:"), tfpass ) },
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
        content += new Button("Test") {
          onAction = (ae: ActionEvent) => {
            setupGmail()
            val f = Future {
              GmailStuff.doTestodypart()
            }
          }
        }
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
    width = 800
    height = 750
    scene = new Scene {
      content = cont
    }
    cont.prefWidth <== scene.width
    cont.prefHeight <== scene.height
  }

  setButtons(flist = true, getemails = true, rmattach = false)

  override def stopApp(): Unit = {
    GmailStuff.cleanup()
    saveSettings()
    super.stopApp()
    System.exit(0)
  }
}
