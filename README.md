# Gmail attachment remover

This is an app that connects to your Gmail account and removes selected attachments, for instance for large emails, without skrewing up the thread display. Google does not want to support this.

* Before removing an attachment, a local full backup of the email is saved. But you should save your important attachments manually before!
* The removed attachment is replaced by a text-file attachment containing the original attachment filename and size.

## Is it safe to use?

* I use it regularly and have never had problems.
* If your computer crashes or the internet connection fails during operation:
    * In the worst case you have lost the labels of one email, more likely is to have an additional email, but in most cases nothing happens, just start over.
    * You always have a downloaded backup of the email.
* But you should anyway backup your Gmail account with, e.g., [gmvault](http://gmvault.org).
* Of course I cannot take any responsibility if you loose emails etc...
* Privacy: the Gmail login OAuth2 refresh token is saved after authentification in the settings file (path is shown in log on startup). Combined with the app source code, it enables access to your emails on Gmail.
* Mind that the Gmail trash folder is emptied in the process.
* For anything else: the source code is straight forward, so just check it!

## Changelog / Status ##


# Getting started #

## Tutorial
To run it, you need java >= 1.8u40 from https://jdk8.java.net/download.html

Do this on Gmail:

* Optional: assign a certain label to your emails where you want to remove the attachment(s), I use `removeattachments`
    * I make a filter for large emails to assign this automatically. I always save important attachments on disk!
* enable imap access in Gmail's settings, best enable access to all folders.

On your computer:

* download binary version from [here](https://bitbucket.org/wolfgang/gmail-attachment-remover/downloads)
* unzip & run the jar, run it and set it up:
    * Gmail account: You can deal with multiple accounts with different settings. Create a new Account by clicking "Add" and enter your Gmail adress!
    * Select folder for mail backups
    * `limit`: select a maximum number of emails processed, e.g., 100. After this you can check the emails if everything is fine, further the process might take very long and google might interrupt the connection from time to time.
    * `label`: Gmail message label to be searched for, e.g. `removeattachments`. It is removed after attachment removal. If empty, this is ignored.
    * `gmail search`: use a "RAW Gmail search term" to pre-filter emails. See the examples in the drop-down list and [here](https://support.google.com/mail/answer/7190?hl=en) for information.
    * `minimum Attachment size`: Select a minimum size above which attachments will be considered. Important for multiple attachments in mails!
* press `Authenticate account` to perform OAuth2 authentification. A web browser window should open, make sure that you are logged in with the correct gmail account!
* press `Get emails`. The table will contain emails matching the criteria.
* Check the table and remove attachments (or whole emails) from the table if they should not be removed.
* press `Remove attachments` to start the removal procedure.
    * This might take very long because the full messages are downloaded for backup, and Gmail limits bandwidth (~100 kB/s)!

## Run from source:

In addition to java, you need sbt 0.13.

Do simply:

    sbt run


## package application

    sbt packageJavafx

# Used frameworks #

* [scala](http://scala-lang.org) 2.11.4
* [scalafx](http://www.scalafx.org) as wrapper for javafx
* [sbt-javafx](https://github.com/kavedaa/sbt-javafx) for packaging
* [javamail](http://www.oracle.com/technetwork/java/javamail/index.html) for everything about IMAP and Gmail
* [scalaj-http](https://github.com/scalaj/scalaj-http) for oauth communication
* [json-simple](https://code.google.com/p/json-simple/) for oauth parsing

# Details

* You can watch progress in the log window, the log is also written to a file named `sgar-<...>.txt` in the computer's temporary folder.
* If you found a bug, please find the logfile and send it to me via email (it contains partial subjects of mails).
* The settings file `sgarsettings.txt` location is displayed on startup.
* Only attachments which have a "filename" are considered. This excludes effectively html/text message parts.
* IMAP message flags are preserved.
* Gmail message labels are preserved. If the `label` tag (see above) exists, it is removed after attachment removal.
* The backup does NOT contain flags and labels. Could easily be added in gmvault-style.
* During attachment removal, it re-connects every 10 minutes because Gmail is dropping the connection after heavy use.

# Contributors #

Bugreports, Comments and Contributions are of course very welcome, please use the standard methods or email.

# Maintainer #

wolfgang.loeffler@gmail.com
