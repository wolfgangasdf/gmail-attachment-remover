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

import com.sun.mail.gimap._
import com.sun.mail.iap.Argument
import com.sun.mail.imap.IMAPFolder.ProtocolCommand
import com.sun.mail.imap.protocol.IMAPProtocol
import com.sun.mail.imap.{IMAPFolder, IMAPMessage}
import jakarta.mail._
import jakarta.mail.internet._
import javafx.concurrent.Task

import java.io.FileOutputStream
import java.util.Properties
import scala.collection.mutable.ListBuffer
import scala.util.control.Breaks._
import scala.util.matching.Regex


object GmailStuff {
  var backupdir: java.io.File = _
  var username = ""
  var password = ""
  var gmailsearch = ""
  var minbytes = 0
  var limit = 0
  var label = ""
  var allmailfolder = ""
  var trashfolder = ""
  var doBackup = false

  var store: Store = _
  private var session: Session = _

  private def heads(s: String, len: Int): String = if (s != null) s.substring(0, math.min(len, s.length)) else ""

  class Bodypart( val contentID: String, val filename: String, val filesize: Int, val contentType: String, val disposition: String)

  class ToDelete(val gmid: Long,
                 val bodyparts: ListBuffer[Bodypart],
                 val from: String,
                 val subject: String,
                 val date: String) {
    override def toString: String = gmid.toString + ": " + heads(subject, 10) + "; bps: " + bodyparts.map(bp => bp.contentID).mkString(",")
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

  def duration(milisecs: Long): String = {
    java.time.Duration.ofSeconds(scala.math.round(milisecs.toDouble / 1000)).toString
      .replace( "PT" , "" )
      .replace( "H" , " hours " )
      .replace( "M" , " minutes " )
      .replace( "S" , " seconds " )
      .stripTrailing()
  }

  // this loops over all bodyparts and executes bpaction(mimeBodyPart), and start/endmultipart()
  def handleMessage(gm: GmailMessage, startmultipart: String /*subtype*/ => Unit = _ => {}, endmultipart: () => Unit = () => {}, bpaction: MimeBodyPart => Unit): Unit = {
    val mp = gm.getContent
    mp match {
      case mmpm: MimeMultipart =>
        for (i <- 0 until mmpm.getCount) {
          val bp = mmpm.getBodyPart(i).asInstanceOf[MimeBodyPart]
          println(s"XXX found bp: ty=${bp.getContentType}, fn=${bp.getFileName}, si=${bp.getSize}, disp=${bp.getDisposition}, cid=${bp.getContentID}, cmd5=${bp.getContentMD5}")
          if (bp.getContentType.startsWith("multipart/")) {
            val mp2 = bp.getContent
            mp2 match {
              case mmpm2: MimeMultipart =>
                val subtypePattern: Regex = """(?s)^multipart/(\w*);.*""".r
                val subtypePattern(subtype) = mmpm2.getContentType
                startmultipart(subtype)
                for (i <- 0 until mmpm2.getCount) {
                  val bp = mmpm2.getBodyPart(i).asInstanceOf[MimeBodyPart]
                  println(s"YYY found bp: ty=${bp.getContentType}, fn=${bp.getFileName}, si=${bp.getSize}, disp=${bp.getDisposition}, cid=${bp.getContentID}, cmd5=${bp.getContentMD5}")
                  bpaction(bp)
                }
                endmultipart()
              case _ => throw new Exception("  unknown mp.class = " + mp.getClass)
            }
          } else
            bpaction(bp)
        }
      case _ => throw new Exception("  unknown mp.class = " + mp.getClass)
    }
  }

  def getToDelete: Task[ListBuffer[ToDelete]] = new javafx.concurrent.Task[ListBuffer[ToDelete]] {
    override def call(): ListBuffer[ToDelete] = {
      val dellist = new ListBuffer[ToDelete]()
      println("Find requested emails with attachments...")
      reconnect()
      val inbox = store.getFolder(s"[Gmail]/$allmailfolder")
      inbox.open(Folder.READ_ONLY)
      val msgs = inbox.search(new GmailRawSearchTerm(gmailsearch + (if (label.isEmpty) "" else " label:" + label)))
      println(s" ${msgs.length } emails found matching the criteria, querying the first limit=$limit emails only!")
      var n = msgs.length
      if (n > limit) n = limit
      var count = 0
      val start = System.currentTimeMillis
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
          val elapsed = System.currentTimeMillis - start
          var text = s"(processed $count of $n emails in " + duration(elapsed) + ")"
          if (count > 0) text = duration(elapsed / count * n - elapsed + 1000) + " left " + text
          updateMessage(text)
          updateProgress(count, n)
          handleMessage(gm, bpaction = bp => {
            if (bp.getSize > minbytes && bp.getContentID != null) {
              bps += new Bodypart(bp.getContentID, bp.getFileName, bp.getSize, bp.getContentType, bp.getDisposition)
              dodelete = true
            }
          })
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
      if (dellist.isEmpty) return
      println("deleting attachments of " + dellist.length + " emails...")
      var count = 0
      val n = dellist.length
      val start = System.currentTimeMillis
      for (todel <- dellist) {
        if (isCancelled) {
          println("CANCELLED!!!")
          throw new InterruptedException("doDelete cancelled!")
        }
        count += 1
        // re-connect for each message, gmail drops connection :-(
//        if (startns == -1 || (System.nanoTime - startns) / 1e9 > 10 * 60) {
//          // re-open connection incl auth after 10 minutes... Gmail seems to drop it from time to time.
          println("----- Re-connect each time...")
          if (store.isConnected) store.close()
          reconnect()
          allmail = store.getFolder(s"[Gmail]/$allmailfolder").asInstanceOf[IMAPFolder]
          trash = store.getFolder(s"[Gmail]/$trashfolder").asInstanceOf[IMAPFolder]
          allmail.open(Folder.READ_WRITE)
          startns = System.nanoTime
          println("----- Re-connected!")
//        }
        println(s"[$count/${dellist.length }] gmid=${todel.gmid } subj=${todel.subject } date=${todel.date }")

        val msg = allmail.search(new GmailMsgIdTerm(todel.gmid)).head

        // backup labels & flags (do before any msg download which sets SEEN flag!)
        val oldlabels = msg.asInstanceOf[GmailMessage].getLabels.toBuffer[String]
        val oldflags = msg.getFlags

        if (doBackup) { // slow since whole message is downloaded
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
        }

        val newmsg = { // with backup, slow since whole message is downloaded
          val gmmsg = msg.asInstanceOf[GmailMessage]
          // https://pastebin.com/vtKcas0K
          val multipart = gmmsg.getContent.asInstanceOf[Multipart]

          // create replacement message with old headers
          val newmsg = new MimeMessage(session)
          // preserve threads: https://developers.google.com/gmail/api/guides/threads#adding_drafts_and_messages_to_threads
          newmsg.setReplyTo(gmmsg.getReplyTo) // add to thread!

          import scala.jdk.CollectionConverters._
          for (h <- gmmsg.getAllHeaders.asScala) {
            newmsg.setHeader(h.getName, h.getValue)
          }

          val mmpm = new MimeMultipart()
          newmsg.setContent(mmpm)

          var nestedmmp: MimeMultipart = null // if not null, bpaction adds to this!

          handleMessage(gmmsg, startmultipart = subtype => {
            nestedmmp = new MimeMultipart(subtype)
          }, endmultipart = () => {
            val multiBodyPart = new MimeBodyPart()
            multiBodyPart.setContent(nestedmmp)
            mmpm.addBodyPart(multiBodyPart)
            nestedmmp = null
          },
            bpaction = bp => {
            if (todel.bodyparts.exists(_.contentID == bp.getContentID)) {
              println("add attachment dummy!")
              val nbp = new MimeBodyPart()
              nbp.setHeader("Content-Type", "text/plain")
              nbp.setFileName(bp.getFileName + ".txt")
              nbp.setText("Removed attachment <" + bp.getFileName + "> of size <" + bp.getSize + ">")
              (if (nestedmmp == null) mmpm else nestedmmp).addBodyPart(nbp)
            } else {
              println("add attachment!")
              (if (nestedmmp == null) mmpm else nestedmmp).addBodyPart(bp)
            }
          })

          // save changes
          newmsg.saveChanges()
          newmsg
        }

        println("putting message back into gmail...")
        val resm = allmail.addMessages(Array(newmsg)).head
        val newgmailid = resm.asInstanceOf[GmailMessage].getMsgId
        println(" newmsg gmail id=" + newgmailid)

        println("moving message to trash in gmail...")
        allmail.copyMessages(Array(msg), trash) // https://stackoverflow.com/a/53945146

        // empty trash: seems not to be needed, mail is nicely in trash, message thread is fine!
        //        trash.open(Folder.READ_WRITE)
        //        val tmsgs = trash.getMessages
        //        for (tmsg <- tmsgs) tmsg.setFlag(Flags.Flag.DELETED, true)
        //        trash.close(true)

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
                throw new Exception("error setting labels of email!")
              }
              null
            }
          })
        }

        val elapsed = System.currentTimeMillis - start
        var text = s"(processed $count of $n emails in " + duration(elapsed) + ")"
        if (count > 0) text = duration(elapsed / count * n - elapsed + 1000) + " left " + text
        updateMessage(text)
        updateProgress(count, n)

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
