# Gmail attachment remover

This is an app that connects to your Gmail account via imap and removes selected large attachments, without screwing up conversations/threads/labels.

* Before removing an attachment, a local full backup of the raw email is saved. But you should save your important attachments manually before!
* The removed attachment is replaced by a text-file attachment containing the original attachment filename and size.


## Tutorial

Do this in Gmail:

* Enable imap access in Gmail's settings, best enable access to all folders.
* Optional: assign a label (e.g., `removeattachments`) to emails where large attachment(s) should be removed (e.g. via filter on size in gmail). Mind that Sgar can't remove this label if you use the `conversation view` in Gmail, because all emails in a conversation get the same label.

On your computer:

* [Download a zip](https://github.com/wolfgangasdf/gmail-attachment-remover/releases), extract it somewhere and double-click the app (Mac) or run
`bin/GmailAttachmentRemover.bat` (Windows) or `bin/GmailAttachmentRemover` (Linux).
* Settings:
    * Gmail account (email): You can deal with multiple accounts with different settings.
    * Password: I suggest to use "application specific passwords" just click the button and get a password for "Mail".
    * Select a folder for mail backups.
    * `limit`: select a maximum number of emails processed, e.g., 100. After this you can check the emails if everything is fine, further the process might take very long and google might interrupt the connection from time to time.
    * `label`: Gmail message label to be searched for, e.g. `removeattachments`. It is removed after attachment removal (doesn't work in conversation view, see above). If empty, this is ignored.
    * `gmail search`: use a "RAW Gmail search term" to pre-filter emails. See the examples in the drop-down list and [here](https://support.google.com/mail/answer/7190?hl=en) for information.
        `size:1MB has:attachment older_than:12m -in:inbox` is a good choice: mails that are small, in the inbox, within the last 12 month, or without attachment, are skipped.
    * `minimum Attachment size`: Select a minimum size above which attachments will be considered. Important for multiple attachments in mails!
* Click `Find emails`. The table will contain emails matching the criteria.
* Check the table and remove rows from the table if they should not be processed.
* Click `Remove attachments` to start the removal procedure.
    * This might take very long because the full messages are downloaded for backup, and Gmail limits bandwidth (~500 kB/s)!


## Is it safe to use?

* The Gmail trash folder is emptied in the process.
* The password is saved in clear text in the settings file.
* It works very well here since >5 years, but Google might change something at any time which could break it.
* If the software or connection is interrupted:
    * In the worst case you have lost the labels of one email, more likely is to have an additional email, but in most cases nothing happens, just start over.
    * You always have a downloaded backup of the email.
* Probably, you should always have a backup your Gmail account with, e.g., [gmvault](http://gmvault.org); however, mind that this is not incremental.
* Of course I cannot take any responsibility for anything.


## How to develop, compile & package

* Get Java 13 from https://jdk.java.net
* Clone the repository
* I use the free community version of [IntelliJ IDEA](https://www.jetbrains.com/idea/download/) with the scala
plugin for development, just open the project to get started.

Packaging:

* Download JDKs for the other platforms (and/or adapt `cPlatforms` in `build.gradle.kts`), extract them and set the environment variables to it:
  * `export JDK_MAC_HOME=...`, `export JDK_WIN_HOME=...`, `export JDK_LINUX_HOME=...`
* Package for all platforms: `./gradlew clean dist`. The resulting files are in `build/crosspackage`


## Used frameworks

* [scala](http://scala-lang.org)
* [scalafx](http://www.scalafx.org) as wrapper for javafx
* [javamail](http://www.oracle.com/technetwork/java/javamail/index.html) for everything about IMAP and Gmail
* [Runtime plugin](https://github.com/beryx/badass-runtime-plugin) to make runtimes with JRE

## Notes

* Only attachments which have a "filename" are considered. This excludes effectively html/text message parts.
* IMAP message flags are preserved.
* Gmail message labels are preserved. If the `label` tag (see above) exists, it is removed after attachment removal (doesn't work for conversation view).
* The processed mails get a new message-ID, which doesn't matter.
* On mac, the computer doesn't sleep during "Find emails" and "Remove attachments"
* License: GPL
