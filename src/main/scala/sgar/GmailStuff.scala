package sgar

import org.json.simple.{JSONObject, JSONValue}

import scalaj.http.Http

import scala.collection.mutable.ListBuffer
import scala.util.control.Breaks._

import java.io.{FileInputStream, FileOutputStream}
import java.util.Properties
import java.net.{URLDecoder, URLEncoder}
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
  var gmailsearch = ""
  var minbytes = 0
  var limit = 0
  var label = ""
  var refreshtoken = ""

  var store: Store = null
  var session: Session = null

  // OAuth2
  val clientid = "217209351804-pf92dc077qrvtotiro7b9lcl6pjrhfhq.apps.googleusercontent.com"
  val clientsecret = "WqZuf6h_xD0al4vH5jolJyds"

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
    println(" getting oauth2 access token...")
    if (refreshtoken == null) throw new Exception("refresh token error, please re-authenticate!")
    val oauth2_access_token = OAuth2google.RefreshTokens(clientid, clientsecret, refreshtoken)
    println(" connecting to gmail...")

    val props = new Properties()
    props.put("mail.store.protocol", "gimaps")
    props.put("mail.gimaps.sasl.enable", "true")
    props.put("mail.gimaps.sasl.mechanisms", "XOAUTH2")
    props.put("mail.imaps.sasl.mechanisms.oauth2.oauthToken", oauth2_access_token)
    session = Session.getInstance(props)

    store = session.getStore("gimaps")
    store.connect("imap.gmail.com", username, oauth2_access_token)
    println("connected: " + store.isConnected)
  }

  def getAllFolders: ListBuffer[String] = {
    val lb = new ListBuffer[String]()
    try {
      println("Get all gmail folders...")
      connect()
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
    } catch {
      case e: Exception => e.printStackTrace()
    } finally {
      store.close()
    }
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
        println("gmid=" + todel.gmid + " subj=" + todel.subject + " date=" + todel.date)

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
        if (oldlabels.isEmpty) {
          println("no labels to restore!")
        } else {
          println("Restoring labels (without tag-label): " + oldlabels.mkString(";"))
          inbox.doCommand(new ProtocolCommand {
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
      inbox.close(true)
      println("Removing attachments finished!")
    } catch {
      case e: Exception => e.printStackTrace()
    } finally {
      store.close()
    }
  }

  def doTest() {
    try {
      connect()
      val inbox = store.getFolder("[Gmail]/All Mail").asInstanceOf[IMAPFolder]
      inbox.open(Folder.READ_WRITE)

      println("gmailsearch=" + gmailsearch)
      val msgs = inbox.search(new GmailMsgIdTerm("1488957505766432509".toLong))
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

  def cleanup(): Unit = {
    if (store != null) if (store.isConnected) store.close()
  }
}

object OAuth2google {
  // based on http://google-mail-oauth2-tools.googlecode.com/svn/trunk/python/oauth2.py

  val GOOGLE_ACCOUNTS_BASE_URL = "https://accounts.google.com"

  // Hardcoded dummy redirect URI for non-web apps.
  val REDIRECT_URI = "urn:ietf:wg:oauth:2.0:oob"

  def AccountsUrl(command: String) = s"$GOOGLE_ACCOUNTS_BASE_URL/$command"

  def UrlEscape(text: String) = URLEncoder.encode(text, "UTF-8")

  def UrlUnescape(text: String) = URLDecoder.decode(text, "UTF-8")

  def FormatUrlParams(params: Seq[(String, String)]) = {
    params map { case (k, v) => s"$k=${UrlEscape(v)}" } mkString "&"
  }

  def GeneratePermissionUrl(client_id: String, scope: String ="https://mail.google.com/") = {
    val params = Seq(
      ("client_id", client_id),
      ("redirect_uri", REDIRECT_URI),
      ("scope", scope),
      ("response_type", "code"))
    s"${AccountsUrl("o/oauth2/auth")}?${FormatUrlParams(params)}"
  }

  def GenURL(base: String, params: Seq[(String, String)]) = {
    s"$base?${FormatUrlParams(params)}"
  }

  def AuthorizeTokens(client_id: String, client_secret: String, authorization_code: String) = {
    val params = Seq(
      ("client_id", client_id),
      ("client_secret", client_secret),
      ("code", authorization_code),
      ("redirect_uri", REDIRECT_URI),
      ("grant_type", "authorization_code"))
    val response = Http(AccountsUrl("o/oauth2/token")).postForm(params).asString
    if (!response.isSuccess) throw new Exception("error retrieving auth token response = " + response)
    val r1 = JSONValue.parse(response.body).asInstanceOf[JSONObject]
    (r1.get("access_token").toString, r1.get("refresh_token").toString)
  }

  def RefreshTokens(client_id: String, client_secret: String, refresh_token: String) = {
    val params = Seq(
      ("client_id", client_id),
      ("client_secret", client_secret),
      ("refresh_token", refresh_token),
      ("grant_type", "refresh_token"))
    val response = Http(AccountsUrl("o/oauth2/token")).postForm(params).asString
    if (!response.isSuccess) throw new Exception("error retrieving refresh token response = " + response)
    val r1 = JSONValue.parse(response.body).asInstanceOf[JSONObject]
    r1.get("access_token").toString
  }

}