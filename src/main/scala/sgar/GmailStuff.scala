package sgar

import scala.collection.mutable.ListBuffer
import scala.util.control.Breaks._

import java.io.{FileInputStream, FileOutputStream}

import javax.mail._
import javax.mail.internet._
import com.sun.mail.gimap._
import com.sun.mail.iap.Argument
import com.sun.mail.imap.{IMAPMessage, IMAPFolder}
import com.sun.mail.imap.IMAPFolder.ProtocolCommand
import com.sun.mail.imap.protocol.IMAPProtocol

/*
 * all public useful methods have their own exception handling, i.e., they can be called in another thread (e.g., future)!
 *
 * flags: gmail seems to use only "flagged" == starred and "seen" == read
 *
 */

object GmailStuff {
  var backupdir: java.io.File = null
  var username = ""
  var password = ""
  var gmailsearch = ""
  var minbytes = 0
  var limit = 0
  var label = ""

  val props = System.getProperties
  //  props.setProperty("mail.store.protocol", "gimap")
  val session = Session.getDefaultInstance(props, null)
  val store = session.getStore("gimaps")

  def heads(s: String, len: Int) = s.substring(0, math.min(len, s.length))

  class Bodypart(val bpi: Int, val filename: String, val filesize: Int, val contentType: String)

  class ToDelete(val gmid: Long,
                 val bodyparts: ListBuffer[Bodypart],
                 val from: String,
                 val subject: String,
                 val date: String) {
    override def toString: String = gmid.toString + ": " + heads(subject, 10) + "; bps: " + bodyparts.map(bp => bp.bpi).mkString(",")
  }

  def flagToString(f: Flags.Flag) = {
    f match {
      case Flags.Flag.ANSWERED => "answered"
      case Flags.Flag.DELETED => "deleted"
      case Flags.Flag.DRAFT => "draft"
      case Flags.Flag.FLAGGED => "flagged"
      case Flags.Flag.RECENT => "recent"
      case Flags.Flag.SEEN => "seen"
      case Flags.Flag.USER => "user"
    }
  }

  private def connect() {
    println("connecting...")
    store.connect("imap.gmail.com", username, password)
    println("connected: " + store.isConnected)
  }

  def getAllFolders: ListBuffer[String] = {
    println("Get all gmail folders...")
    connect()
    val lb = new ListBuffer[String]()
    def listFolderRec(folder: Folder, level: Int): Unit = {
      val s = folder.getFullName
      println(("." * level) + s)
      lb += s
      val childs = folder.list
      for (ch <- childs) listFolderRec(ch, level + 1)
    }
    listFolderRec(store.getDefaultFolder, 0)
    store.close()
    println("Get all gmail folders finished!")
    lb
  }

  def getToDelete: ListBuffer[ToDelete] = {
    val dellist = new ListBuffer[ToDelete]()
    try {
      println("Find requested emails with attachments...")
      connect()
      val inbox = store.getFolder("[Gmail]/All Mail")
      inbox.open(Folder.READ_WRITE)
      val msgs = inbox.search(new GmailRawSearchTerm(gmailsearch + (if (label.isEmpty) "" else " label:" + label)))
      var count = 0
      breakable {
        for (message <- msgs) {
          var dodelete = false
          val bps = new ListBuffer[Bodypart]()
          val gm = message.asInstanceOf[GmailMessage]
          println(s"checking gid=${gm.getMsgId} subj=${message.getSubject} labels:${gm.getLabels.mkString(",")}")
          val mp = gm.getContent
          mp match {
            case mmpm: MimeMultipart =>
              for (i <- 0 to mmpm.getCount - 1) {
                val bp = mmpm.getBodyPart(i)
                if (bp.getSize > minbytes && bp.getFileName != null) {
                  bps += new Bodypart(i, bp.getFileName, bp.getSize, bp.getContentType)
                  dodelete = true
                }
              }
            case _ => println("  unknown mp.class = " + mp.getClass)
          }
          if (dodelete) {
            dellist += new ToDelete(gm.getMsgId, bps, gm.getFrom.head.toString, gm.getSubject, gm.getSentDate.toString)
            count += 1
          }
          if (count > limit) break()
        }
      }
      inbox.close(true)
      println("Found all requested emails!")
    } catch {
      case e: Exception => e.printStackTrace()
    } finally {
      store.close()
    }
    dellist
  }

  def doDelete(dellist: ListBuffer[ToDelete]) {
    try {
      connect()
      println("deleting attachments of " + dellist.length + " emails...")
      val inbox = store.getFolder("[Gmail]/All Mail").asInstanceOf[IMAPFolder]
      val trash = store.getFolder("[Gmail]/Trash").asInstanceOf[IMAPFolder]
      inbox.open(Folder.READ_WRITE)
      for (todel <- dellist) {
        println("gmid=" + todel.gmid)

        val msg = inbox.search(new GmailMsgIdTerm(todel.gmid)).head

        // backup labels & flags (do before any msg download which sets SEEN flag!)
        val oldlabels = msg.asInstanceOf[GmailMessage].getLabels.toBuffer[String]
        val oldflags = msg.getFlags

        println("backup message...")
        val tmpfile = new java.io.File(backupdir, todel.gmid.toString + ".msg")
        println("  backupfile=" + tmpfile.getPath)
        val os = new FileOutputStream(tmpfile)
        msg.writeTo(os)
        os.close()

        println("deleting message in gmail...")
        inbox.copyMessages(Array(msg), trash)
        trash.open(Folder.READ_WRITE)
        val tmsgs = trash.getMessages
        for (tmsg <- tmsgs) tmsg.setFlag(Flags.Flag.DELETED, true)
        trash.close(true)

        println("re-creating message...")
        val is = new FileInputStream(tmpfile)
        val newmsg = new MimeMessage(session, is)
        is.close()

        println("remove attachments...")
        val mmpm = newmsg.getContent.asInstanceOf[MimeMultipart]
        // do in reverse order!
        for (bpdel <- todel.bodyparts.reverse) mmpm.removeBodyPart(bpdel.bpi)
        // re-add as empty attachment
        for (bpdel <- todel.bodyparts) {
          val nbp = new MimeBodyPart()
          nbp.setHeader("Content-Type", "text/plain")
          nbp.setFileName(bpdel.filename + ".txt")
          nbp.setText("Removed attachment <" + bpdel.filename + "> of size <" + bpdel.filesize + ">")
          mmpm.addBodyPart(nbp)
        }

        // save changes
        newmsg.saveChanges()

        println("putting message back into gmail...")
        val resm = inbox.addMessages(Array(newmsg)).head
        val newgmailid = resm.asInstanceOf[GmailMessage].getMsgId
        println(" newmsg gmail id=" + newgmailid)

        // re-open folder, this is essential for doCommand() to work below!
        inbox.close(true)
        inbox.open(Folder.READ_WRITE)

        val nmsgx = inbox.search(new GmailMsgIdTerm(newgmailid)).head
        val nmsg = nmsgx.asInstanceOf[IMAPMessage]

        // restore flags
        inbox.setFlags(Array(nmsgx), oldflags, true)

        if (label != "") { // remove tag label!
          oldlabels -= label
        }
        println("Restoring labels (without tag-label): " + oldlabels.mkString(";"))
        inbox.doCommand(new ProtocolCommand {
          override def doCommand(protocol: IMAPProtocol): AnyRef = {
            val args = new Argument()
            args.writeString("" + nmsg.getMessageNumber)
            args.writeString("+X-GM-LABELS")
            for (lab <- oldlabels) args.writeString(lab)
            val r = protocol.command("STORE", args)
            if (!r.last.isOK) throw new MessagingException("error setting labels of email!")
            null
          }
        })

      }
      inbox.close(true)
      println("Removing attachments finished!")
    } catch {
      case e: Exception => e.printStackTrace()
    } finally {
      store.close()
    }
  }

  def doTestFlags() {
    try {
      connect()
      val inbox = store.getFolder("[Gmail]/All Mail").asInstanceOf[IMAPFolder]
      inbox.open(Folder.READ_WRITE)

      println("gmailsearch=" + gmailsearch)
      val msgs = inbox.search(new GmailMsgIdTerm(1488957505766432509L))
      for (m <- msgs) println("msg: " + m.getSubject + m.getSentDate)

      val nmsg = msgs.head.asInstanceOf[IMAPMessage]
      println("nmsg: " + nmsg.getMessageID + " num=" + nmsg.getMessageNumber + " gid=" + nmsg.asInstanceOf[GmailMessage].getMsgId)

      val flags = nmsg.getFlags
      println("sflags = " + flags.getSystemFlags.map(f => flagToString(f)).mkString(";"))
      println("uflags = " + flags.getUserFlags.mkString(";"))

      inbox.close(true)
      store.close()
      println("finished!")
    } catch {
      case e: Exception => e.printStackTrace()
    } finally {
      println("store close")
      store.close()
    }
  }

  def doTestodypart() {
    try {
      connect()
      val inbox = store.getFolder("[Gmail]/All Mail").asInstanceOf[IMAPFolder]
      inbox.open(Folder.READ_WRITE)

      println("gmailsearch=" + gmailsearch)
      val msgs = inbox.search(new GmailMsgIdTerm(1488957505766432509L))
      for (m <- msgs) println("msg: " + m.getSubject + m.getSentDate)

      val nmsg = msgs.head.asInstanceOf[IMAPMessage]
      println("nmsg: " + nmsg.getMessageID + " num=" + nmsg.getMessageNumber + " gid=" + nmsg.asInstanceOf[GmailMessage].getMsgId)

      val mmpm = nmsg.getContent.asInstanceOf[MimeMultipart]

      for (bpi <- 1 to mmpm.getCount - 1) println("  bp " + bpi + " : " + mmpm.getBodyPart(bpi).getContent.toString)

      val nbp = new MimeBodyPart()
      nbp.setHeader("Content-Type", "text/html")
      nbp.setText("huhuhuhuhuhuhuhuh")

      mmpm.addBodyPart(nbp)

//      nmsg.saveChanges()

      inbox.close(true)
      store.close()
      println("finished!")
    } catch {
      case e: Exception => e.printStackTrace()
    } finally {
      println("store close")
      store.close()
    }
  }

  def doTestLabels() {
    try {
      connect()
      val inbox = store.getFolder("[Gmail]/All Mail").asInstanceOf[IMAPFolder]
      inbox.open(Folder.READ_WRITE)

      println("gmailsearch=" + gmailsearch)
      val msgs = inbox.search(new GmailMsgIdTerm(1488957505766432509L))
      for (m <- msgs) println("msg: " + m.getSubject + m.getSentDate)

      val nmsg = msgs.head.asInstanceOf[IMAPMessage]
      println("nmsg: " + nmsg.getMessageID + " num=" + nmsg.getMessageNumber + " gid=" + nmsg.asInstanceOf[GmailMessage].getMsgId)

      inbox.doCommand(new ProtocolCommand {
        override def doCommand(protocol: IMAPProtocol): AnyRef = {
          val args = new Argument()
          args.writeString("" + nmsg.getMessageNumber)
          args.writeString("+X-GM-LABELS")
          args.writeString("test1")
          args.writeString("test1/test1sub1")
          val r = protocol.command("STORE", args)
          for (rr <- r) println(" response: " + rr.toString)
          null
        }
      })
      inbox.close(true)
      store.close()
      println("finished!")
    } catch {
      case e: Exception => e.printStackTrace()
    } finally {
      store.close()
    }
  }

  def cleanup(): Unit = {
    if (store.isConnected) store.close()
  }
}