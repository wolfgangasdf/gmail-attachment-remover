package sgar

import java.io.{FileInputStream, FileOutputStream}

import com.sun.mail.gimap._
import javax.mail._
import javax.mail.internet._

import scala.collection.mutable.ListBuffer
import scala.util.control.Breaks._


object GmailStuff {
  var backupdir: java.io.File = null
  var username = ""
  var password = ""
  var gmailsearch = ""
  var minbytes = 0
  var limit = 0

  val props = System.getProperties
  //  props.setProperty("mail.store.protocol", "gimap")
  val session = Session.getDefaultInstance(props, null)
  val store = session.getStore("gimap")

  def heads(s: String, len: Int) = s.substring(0, math.min(len, s.length))

  class Bodypart(val bpi: Int, val filename: String, val filesize: Int, val contentType: String)

  class ToDelete(val gmid: Long,
                 val bodyparts: ListBuffer[Bodypart],
                 val from: String,
                 val subject: String,
                 val date: String) {
    override def toString: String = gmid.toString + ": " + heads(subject, 10) + "; bps: " + bodyparts.map(bp => bp.bpi).mkString(",")
  }

  def connect() {
    store.connect("imap.gmail.com", username, password)
  }

  def getAllFolders: ListBuffer[String] = {
    val lb = new ListBuffer[String]()
    def listFolderRec(folder: Folder, level: Int): Unit = {
      lb += ("." * level) + folder.getFullName
      val childs = folder.list
      for (ch <- childs) listFolderRec(ch, level + 1)
    }
    listFolderRec(store.getDefaultFolder, 1)
    lb
  }

  def getToDelete: ListBuffer[ToDelete] = {
    val dellist = new ListBuffer[ToDelete]()
    val inbox = store.getFolder("[Gmail]/All Mail")
//    val inbox = store.getFolder("removeattachment")
    inbox.open(Folder.READ_WRITE)

    val msgs = inbox.search(new GmailRawSearchTerm(gmailsearch))
    //    val msgs = inbox.getMessages
    var idx = 0
    breakable { for (message <- msgs) {
      var dodelete = false
      val bps = new ListBuffer[Bodypart]()
      val gm = message.asInstanceOf[GmailMessage]
      println(s"${message.getSubject} gid=${gm.getMsgId} labels:${gm.getLabels.mkString(",")}")
      val mp = gm.getContent
      println("  mp.class = " + mp.getClass)
      mp match {
        case mmpm: MimeMultipart =>
          for (i <- 0 to mmpm.getCount - 1) {
            val bp = mmpm.getBodyPart(i)
            println(s"  i=$i fn=" + bp.getFileName + " size=" + bp.getSize + " ct=" + bp.getContentType)
            if (bp.getSize > minbytes) {
              bps += new Bodypart(i, bp.getFileName, bp.getSize, bp.getContentType)
              dodelete = true
            }
          }
        case _ =>
      }
      if (dodelete) dellist += new ToDelete(gm.getMsgId, bps, gm.getFrom.head.toString, gm.getSubject, gm.getSentDate.toString)
      idx += 1
      if (idx > limit) break()
    } }
    inbox.close(true)
    dellist
  }

  def doDelete(dellist: ListBuffer[ToDelete]) {
//    val inbox = store.getFolder("removeattachment")
    val inbox = store.getFolder("[Gmail]/All Mail")
    val trash = store.getFolder("[Gmail]/Trash")
    inbox.open(Folder.READ_WRITE)
    for (todel <- dellist) {
      println("gmid=" + todel.gmid)
      val msg = inbox.search(new GmailMsgIdTerm(todel.gmid)).head

      println("backup message...")
      val tmpfile = new java.io.File(backupdir, todel.gmid.toString + ".msg")
      println("  tmpfile=" + tmpfile.getPath)
      val os = new FileOutputStream(tmpfile)
      msg.writeTo(os)
      os.close()

      println("deleting message in gmail...")
      println("  bps = " + msg.getContent.asInstanceOf[MimeMultipart].getCount)
      inbox.copyMessages(Array(msg), trash)
      trash.open(Folder.READ_WRITE)
      val tmsgs = trash.getMessages
      for (tmsg <- tmsgs) tmsg.setFlag(Flags.Flag.DELETED, true)
      trash.close(true)

      println("re-creating message...")
      val is = new FileInputStream(tmpfile)
      val newmsg = new MimeMessage(session, is)
      is.close()
      println("  bps = " + newmsg.getContent.asInstanceOf[MimeMultipart].getCount)

      println("remove attachments...")
      println("  bps = " + newmsg.getContent.asInstanceOf[MimeMultipart].getCount)
      val mmpm = newmsg.getContent.asInstanceOf[MimeMultipart]
      // TODO check if I need to reverse!
      for (bpdel <- todel.bodyparts.reverse) mmpm.removeBodyPart(bpdel.bpi)
      newmsg.saveChanges()
      println("  bps = " + newmsg.getContent.asInstanceOf[MimeMultipart].getCount)

      println("putting message back into gmail...")
      inbox.appendMessages(Array(newmsg))
    }
    inbox.close(true)
  }

  def cleanup(): Unit = {
    if (store.isConnected) store.close()
  }
}