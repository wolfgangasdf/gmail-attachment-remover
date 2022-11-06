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

import scala.collection.mutable.ListBuffer
import scala.util.control.Breaks._
import java.io.{FileInputStream, FileOutputStream}
import java.util.Properties

import jakarta.mail._
import jakarta.mail.internet._
import com.sun.mail.gimap._
import com.sun.mail.iap.Argument
import com.sun.mail.imap.{IMAPFolder, IMAPMessage}
import com.sun.mail.imap.IMAPFolder.ProtocolCommand
import com.sun.mail.imap.protocol.IMAPProtocol
import javafx.concurrent.Task


object GmailStuff {
  var backupdir: java.io.File = _
  var username = ""
  var password = ""
  var gmailsearch = ""
  var minbytes = 0
  var limit = 0
  var label = ""

  var store: Store = _
  var session: Session = _

  def heads(s: String, len: Int): String = if (s != null) s.substring(0, math.min(len, s.length)) else ""

  class Bodypart(val bpi: Int, val filename: String, val filesize: Int, val contentType: String)

  class ToDelete(val gmid: Long,
                 val bodyparts: ListBuffer[Bodypart],
                 val from: String,
                 val subject: String,
                 val date: String) {
    override def toString: String = gmid.toString + ": " + heads(subject, 10) + "; bps: " + bodyparts.map(bp => bp.bpi).mkString(",")
  }

  def flagToString(f: Flags.Flag): String = {
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

  private def reconnect(): Unit = {
    println("(re)connecting...")
    if (store != null) if (store.isConnected) store.close()
    println(" connecting to gmail...")

    val props = new Properties()
    props.put("mail.store.protocol", "gimaps")
    session = Session.getInstance(props)

    store = session.getStore("gimaps")
    store.connect("imap.gmail.com", username, password)
    println("connected: " + store.isConnected)
  }

  def getAllFolders: ListBuffer[String] = {
    val lb = new ListBuffer[String]()
    println("Get all gmail folders...")
    reconnect()
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

  def getToDelete: Task[ListBuffer[ToDelete]] = new javafx.concurrent.Task[ListBuffer[ToDelete]] {
    override def call(): ListBuffer[ToDelete] = {
      val dellist = new ListBuffer[ToDelete]()
      println("Find requested emails with attachments...")
      reconnect()
      val inbox = store.getFolder("[Gmail]/All Mail")
      inbox.open(Folder.READ_ONLY)
      val msgs = inbox.search(new GmailRawSearchTerm(gmailsearch + (if (label.isEmpty) "" else " label:" + label)))
      println(s" ${msgs.length } emails found matching the criteria, querying the first limit=$limit emails only!")
      var count = 0
      breakable {
        for (message <- msgs) {
          if (isCancelled) {
            println("CANCELLED!!!")
            throw new InterruptedException("getToDelete cancelled!")
          }
          var dodelete = false
          val bps = new ListBuffer[Bodypart]()
          val gm = message.asInstanceOf[GmailMessage]
          println(s"checking gid=${gm.getMsgId } subj=${message.getSubject } labels:${gm.getLabels.mkString(",") }")
          updateMessage(s"checking gid=${gm.getMsgId } subj=${message.getSubject } labels:${gm.getLabels.mkString(",") }")
          val mp = gm.getContent
          mp match {
            case mmpm: MimeMultipart =>
              for (i <- 0 until mmpm.getCount) {
                val bp = mmpm.getBodyPart(i)
                if (bp.getSize > minbytes && bp.getFileName != null) {
                  bps += new Bodypart(i, bp.getFileName, bp.getSize, bp.getContentType)
                  dodelete = true
                }
              }
            case _ => println("  unknown mp.class = " + mp.getClass)
          }
          if (dodelete) {
            dellist += new ToDelete(gm.getMsgId, bps, gm.getFrom.headOption.map(_.toString).getOrElse(""), gm.getSubject, gm.getSentDate.toString)
            count += 1
          }
          if (count >= limit) break()
        }
      }
      inbox.close(true)
      store.close()
      println("Found all requested emails!")
      dellist
    }
  }

  def doDelete(dellist: ListBuffer[ToDelete]): Task[Unit] = new javafx.concurrent.Task[Unit] {
    override def call(): Unit = {
      var startns: Long = -1
      var allmail: IMAPFolder = null
      var trash: IMAPFolder = null
      println("deleting attachments of " + dellist.length + " emails...")
      var count = 0
      for (todel <- dellist) {
        if (isCancelled) {
          println("CANCELLED!!!")
          throw new InterruptedException("doDelete cancelled!")
        }
        count += 1
        // TODO testing: re-connect for each message, gmail drops connection :-(
//        if (startns == -1 || (System.nanoTime - startns) / 1e9 > 10 * 60) {
//          // re-open connection incl auth after 10 minutes... Gmail seems to drop it from time to time.
          println("----- Re-connect each time...")
          if (store.isConnected) store.close()
          reconnect()
          allmail = store.getFolder("[Gmail]/All Mail").asInstanceOf[IMAPFolder]
          trash = store.getFolder("[Gmail]/Trash").asInstanceOf[IMAPFolder]
          allmail.open(Folder.READ_WRITE)
          startns = System.nanoTime
          println("----- Re-connected!")
//        }
        println(s"[$count/${dellist.length }] gmid=${todel.gmid } subj=${todel.subject } date=${todel.date }")

        val msg = allmail.search(new GmailMsgIdTerm(todel.gmid)).head

        // backup labels & flags (do before any msg download which sets SEEN flag!)
        val oldlabels = msg.asInstanceOf[GmailMessage].getLabels.toBuffer[String]
        val oldflags = msg.getFlags

        println("backup message...")
        val backupfile = new java.io.File(backupdir, todel.gmid.toString + ".msg")
        println("  backupfile=" + backupfile.getPath)
        val parent = backupfile.getParentFile
        if (parent != null)
          if (!parent.exists())
            parent.mkdirs()
        val os = new FileOutputStream(backupfile)
        msg.writeTo(os)
        os.close()

        println("re-creating message...")
        val is = new FileInputStream(backupfile)
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
        val resm = allmail.addMessages(Array(newmsg)).head
        val newgmailid = resm.asInstanceOf[GmailMessage].getMsgId
        println(" newmsg gmail id=" + newgmailid)

        println("deleting message in gmail and emptying trash...")
        allmail.copyMessages(Array(msg), trash)
        trash.open(Folder.READ_WRITE)
        val tmsgs = trash.getMessages
        for (tmsg <- tmsgs) tmsg.setFlag(Flags.Flag.DELETED, true)
        trash.close(true)

        println("re-open folder...")
        // re-open folder, this is essential for doCommand() to work below!
        allmail.close(true)
        allmail.open(Folder.READ_WRITE)

        val nmsgx = allmail.search(new GmailMsgIdTerm(newgmailid)).head
        val nmsg = nmsgx.asInstanceOf[IMAPMessage]

        // restore flags like 'unread'
        //println("old system flags=[" + oldflags.getSystemFlags.mkString(";") + "][" + oldflags.getSystemFlags.map(f => flagToString(f)).mkString(";") + "] user flags=" + oldflags.getUserFlags.mkString(";"))
        allmail.setFlags(Array(nmsgx), oldflags, true)

        // restore gmail labels except the 'largemails' label
        println("Restoring labels: " + oldlabels.mkString(";"))
        if (label != "") {
          oldlabels -= label
        }
        if (oldlabels.isEmpty) {
          println("no labels to restore!")
        } else {
          allmail.doCommand(new ProtocolCommand {
            override def doCommand(protocol: IMAPProtocol): AnyRef = {
              val args = new Argument()
              args.writeString("" + nmsg.getMessageNumber)
              args.writeString("+X-GM-LABELS")
              for (lab <- oldlabels) args.writeString(lab)
              val r = protocol.command("STORE", args)
              if (!r.last.isOK) {
                println("ERROR: oldlabel=" + oldlabels)
                println("ERROR: args:\n ")
                println(args)
                println("ERROR: responses:\n ")
                for (rr <- r) println(rr)
                throw new MessagingException("error setting labels of email!")
              }
              null
            }
          })
        }
      }
      allmail.close(true)
      println("Removing attachments finished!")
      store.close()
    }
  }

  def cleanup(): Unit = {
    if (store != null) if (store.isConnected) store.close()
  }
}
