# Scala Gmail attachment remover (Sgar)

This is an app that connects to your Gmail account via imap and removes selected large attachments, without screwing up the conversations/threads/labels.

* Before removing an attachment, a local full backup of the raw email is saved. But you should save your important attachments manually before!
* The removed attachment is replaced by a text-file attachment containing the original attachment filename and size.


## Tutorial

Do this in Gmail:

* Enable imap access in Gmail's settings, best enable access to all folders.
* Optional: assign a label (e.g., `removeattachments`) to emails where large attachment(s) should be removed (e.g. via filter on size in gmail). Mind that Sgar can't remove this label if you use the `conversation view` in Gmail, because all emails in a conversation get the same label.

On your computer:

* Get the [Java JRE](http://www.oracle.com/technetwork/java/javase/downloads/index.html) >= 8u101. Don't forget to untick the [crapware](https://www.google.com/search?q=java+crapware) installer, and/or [disable it permanently](https://www.java.com/en/download/faq/disable_offers.xml)!
* [Download a zip](https://bitbucket.org/wolfgang/gmail-attachment-remover/downloads) for Mac or (Windows, Linux), extract it somewhere and double-click the app (Mac) or
  jar file (Windows, Linux).
* Settings:
    * Gmail account (email): You can deal with multiple accounts with different settings.
    * Select a folder for mail backups.
    * `limit`: select a maximum number of emails processed, e.g., 100. After this you can check the emails if everything is fine, further the process might take very long and google might interrupt the connection from time to time.
    * `label`: Gmail message label to be searched for, e.g. `removeattachments`. It is removed after attachment removal (doesn't work in conversation view, see above). If empty, this is ignored.
    * `gmail search`: use a "RAW Gmail search term" to pre-filter emails. See the examples in the drop-down list and [here](https://support.google.com/mail/answer/7190?hl=en) for information.
        `size:1MB has:attachment older_than:12m -in:inbox` is a good choice: mails that are small, in the inbox, within the last 12 month, or without attachment, are skipped.
    * `minimum Attachment size`: Select a minimum size above which attachments will be considered. Important for multiple attachments in mails!
* Click `Authenticate account` to perform OAuth2 authentication. A web browser window should open, make sure that you are logged in with the correct gmail account!
* Click `Get emails`. The table will contain emails matching the criteria.
* Check the table and remove rows from the table if they should not be processed.
* Click `Remove attachments` to start the removal procedure.
    * This might take very long because the full messages are downloaded for backup, and Gmail limits bandwidth (~500 kB/s)!


## Is it safe to use?

* The Gmail trash folder is emptied in the process!
* It works very well here since >5 years, but Google might change something at any time which could break it.
* If the software or connection is interrupted:
    * In the worst case you have lost the labels of one email, more likely is to have an additional email, but in most cases nothing happens, just start over.
    * You always have a downloaded backup of the email.
* Probably, you should always have a backup your Gmail account with, e.g., [gmvault](http://gmvault.org); however, mind that this is not incremental.
* Of course I cannot take any responsibility for anything.
* Privacy: the Gmail login OAuth2 refresh token is saved after authentification in the settings file (path is shown in log on startup). Combined with the app source code, it enables IMAP access to Gmail.


## How to develop, compile & package

* Get Java JDK >= 8u101
* check out the code (`hg clone ...` or download a zip)
* I use the free community version of [IntelliJ IDEA](https://www.jetbrains.com/idea/download/) with the scala
plugin for development, just open the project to get started.

Run the program from terminal and package it:

* Install the [Scala Build Tool](http://www.scala-sbt.org/)
* Compile and run manually: `sbt run`
* Package for all platforms: `sbt dist`. The resulting files are in `target/`


## Suggestions, bug reports, pull requests, contact
Please use the bitbucket-provided tools for bug reports and contributed code. Anything is welcome!


## Used frameworks

* [scala](http://scala-lang.org)
* [scalafx](http://www.scalafx.org) as wrapper for javafx
* [sbt-javafx](https://github.com/kavedaa/sbt-javafx) for packaging
* [javamail](http://www.oracle.com/technetwork/java/javamail/index.html) for everything about IMAP and Gmail
* [scalaj-http](https://github.com/scalaj/scalaj-http) for oauth communication
* [json-simple](https://code.google.com/p/json-simple/) for oauth parsing

## Notes

* Only attachments which have a "filename" are considered. This excludes effectively html/text message parts.
* IMAP message flags are preserved.
* Gmail message labels are preserved. If the `label` tag (see above) exists, it is removed after attachment removal (doesn't work for conversation view).
* The message gets a new ID, which doesn't matter.
* This program contains a nice example for doing OAuth2 authentification with local webserver redirect callback, in a few lines code (OAuth2google)!
* License: GPL