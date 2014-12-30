package sgar

import java.io.{FileOutputStream, FileInputStream, File}
import javafx.beans.value.ObservableValue
import javafx.scene.control.TreeTableColumn.CellDataFeatures
import javafx.scene.control.{TreeTableColumn, TreeTableView}
import javafx.util.Callback

import sgar.GmailStuff.{Bodypart, ToDelete}

import scala.collection.mutable.ListBuffer

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

/*
wow, this works!!!

TODO:
check if gmail id changes in the process!
make safer, store in todelete also filename etc.
add deleted content filename + size!
probably limit to certain number of mails? (call repetively)
limit to date range
then ask for confirmation, and I am done!
check other labels preserved!!! also starred???
dialog:
  dir chooser for backupdir
  gmail login

 */




object Sgar extends JFXApp {

  val props = new java.util.Properties()
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
          new Button("remove selected rows")
        )
      }
    )
  }

  loadSettings()

  val tfuser = new TextField { prefWidth = 500; text = props.getProperty("username","???") }
  val tfpass = new TextField { prefWidth = 500; text = props.getProperty("password","???") }
  val tfbackupdir = new TextField { prefWidth = 500; text = props.getProperty("backupdir","???") }
  val tfminbytes = new TextField { text = props.getProperty("minbytes","10000") }
  val tfgmailsearch = new ComboBox[String] {
    prefWidth = 500
    val strings = ObservableBuffer("label:removeattachment size:10KB")
    items = strings
    value = props.getProperty("gmailsearch","???")
    editable = true
  }

  val bSelectbackupdir = new Button("select...") {
    onAction = (ae: ActionEvent) => {
      val fcbackupdir = new DirectoryChooser {
        title = "pick backup folder..."
      }
      tfbackupdir.text = fcbackupdir.showDialog(stage).getAbsolutePath
    }
  }

  val settingsPane = new VBox(2.0) {
    fillWidth = true
    content ++= List(
      new HBox { alignment = Pos.CenterLeft ; content ++= List( new Label("email:"), tfuser ) },
      new HBox { alignment = Pos.CenterLeft ; content ++= List( new Label("password:"), tfpass ) },
      new HBox { alignment = Pos.CenterLeft ; content ++= List( new Label("backupfolder:"), tfbackupdir, bSelectbackupdir ) },
      new HBox { alignment = Pos.CenterLeft ; content ++= List( new Label("Gmail search:"), tfgmailsearch ) },
      new HBox { alignment = Pos.CenterLeft ; content ++= List( new Label("minimum Attachment size (bytes):"), tfminbytes ) },
      new HBox {
        alignment = Pos.Center
        content += new Button("Quit") {
          onAction = (ae: ActionEvent) => {
            stopApp()
          }
        }
        content += new Button("Connect...") {
          onAction = (ae: ActionEvent) => {
            GmailStuff.backupdir = new File(tfbackupdir.text.value)
            GmailStuff.username = tfuser.text.value
            GmailStuff.password = tfpass.text.value
            GmailStuff.gmailsearch = tfgmailsearch.selectionModel.value.getSelectedItem
            GmailStuff.minbytes = tfminbytes.text.value.toInt
            GmailStuff.connect()
            val ff = GmailStuff.getAllFolders
            logView.appendText("All folders in Gmail:\n")
            for (f <- ff) logView.appendText(f + "\n")
          }
        }
        content += new Button("Get emails...") {
          onAction = (ae: ActionEvent) => {
            val dellist = GmailStuff.getToDelete
            tiroot.children.clear()
            for (todel <- dellist) {
              val ti = new TreeItem[TreeThing](new TreeThing(todel, null))
              for (bp <- todel.bodyparts) {
                ti.children += new TreeItem[TreeThing](new TreeThing(todel, bp))
              }
              tiroot.children += ti
            }
          }
        }
        content += new Button("test") {
          onAction = (ae: ActionEvent) => {
            val dellist = new ListBuffer[ToDelete]()
            var bps = new ListBuffer[Bodypart]()
            bps ++= List(new Bodypart(0, "fn1", 123, "type1"), new Bodypart(1, "fn2", 1234, "type2"))
            dellist += new ToDelete(1000, bps, "from1", "subj1", "date1")
            bps = new ListBuffer[Bodypart]()
            bps ++= List(new Bodypart(0, "fn11", 1234, "type1"), new Bodypart(1, "fn12", 12345, "type2"))
            dellist += new ToDelete(1001, bps, "from2", "subj2", "date2")


            tiroot.children.clear()
            for (todel <- dellist) {
              val ti = new TreeItem[TreeThing](new TreeThing(todel, null))
              for (bp <- todel.bodyparts) {
                ti.children += new TreeItem[TreeThing](new TreeThing(todel, bp))
              }
              tiroot.children += ti
            }
          }
        }
      }
    )
  }

  val logView = new TextArea {
    text = "Application log"
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
    height = 600
    scene = new Scene {
      content = cont
    }
    cont.prefWidth <== scene.width
    cont.prefHeight <== scene.height
  }

  override def stopApp(): Unit = {
    GmailStuff.cleanup()
    saveSettings()
    super.stopApp()
    System.exit(0)
  }
}
