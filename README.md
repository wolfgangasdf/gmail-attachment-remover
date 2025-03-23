# Gmail attachment remover

This is an app that connects to your Gmail account via imap and removes selected large attachments, without screwing up conversations/threads/labels.

* The removed attachment is replaced by a text-file attachment containing the original attachment filename and size.
* If backup is selected: Before removing an attachment, a local full backup of the raw email is saved (very slow).

## Getting started

Do this in Gmail:

* Enable imap access in Gmail's settings, best enable access to all folders.
* Optional: assign a label (e.g., `removeattachments`) to emails where large attachment(s) should be removed (e.g. via filter on size in gmail). Mind that Sgar can't remove this label if you use the `conversation view` in Gmail, because all emails in a conversation get the same label.

On your computer:

* [Download a zip](https://github.com/wolfgangasdf/gmail-attachment-remover/releases), extract it (on mac, use https://theunarchiver.com/ or fix permissions) somewhere and run it. It is not signed, google for "open unsigned mac/win".
* Settings:
    * Gmail account (email): You can deal with multiple accounts with different settings.
    * Password: I suggest to use "application specific passwords" just click the button and get a password for "Mail".
    * Select a folder for mail backups.
    * `Backup messages`: If selected a backup of the full messages will be downloaded before attachment removal. Very slow.
    * `limit`: select a maximum number of emails processed, e.g., 100. After this you can check the emails if everything is fine, further the process might take very long and google might interrupt the connection from time to time.
    * `label`: Gmail message label to be searched for, e.g. `removeattachments`. It is removed after attachment removal (doesn't work in conversation view, see above). If empty, this is ignored.
    * You can also give custom names for the "All Mail" and "Trash" folders - check the error log if the defaults don't work (e.g., other languages).
    * `gmail search`: use a "RAW Gmail search term" to pre-filter emails. See the examples in the drop-down list and [here](https://support.google.com/mail/answer/7190?hl=en) for information.
        `size:1MB has:attachment older_than:12m -in:inbox` is a good choice: mails that are small, in the inbox, within the last 12 month, or without attachment, are skipped.
    * `minimum Attachment size`: Select a minimum size above which attachments will be considered. Important for multiple attachments in mails!
    * `run caffeinate`: select to prevent sleep during long actions (on mac).
* Click `Find emails`. The table will contain emails matching the criteria.
* Check the table and remove rows from the table if they should not be processed.
* Click `Remove attachments` to start the removal procedure.
    * This might take very long because the full messages are downloaded for backup, and Gmail limits the bandwidth!


## Is it safe to use?

* The password is saved in clear text in the settings file.
* It works very well here since >10 years, but Google might change something at any time which could break it.
* If the software or connection is interrupted:
    * In the worst case you have lost the labels of one email, more likely is to have an additional email, but in most cases nothing happens, just start over.
    * If backup is not disabled: You always have a downloaded backup of the email.
* Probably, you should always have a backup your Gmail account with, e.g., [gmvault](http://gmvault.org); however, mind that this is not incremental.
* Of course, I cannot take any responsibility for anything.


## How to develop, compile & package

* Get Java from https://jdk.java.net
* Clone the repository
* I use the free community version of [IntelliJ IDEA](https://www.jetbrains.com/idea/download/) with the scala
plugin for development, just open the project to get started.

Packaging:

* Package for all platforms: `./gradlew clean dist`. The resulting files are in `build/crosspackage`


## Used frameworks

* [scala](http://scala-lang.org)
* [scalafx](http://www.scalafx.org) as wrapper for javafx
* [javamail](http://www.oracle.com/technetwork/java/javamail/index.html) for everything about IMAP and Gmail
* [Runtime plugin](https://github.com/beryx/badass-runtime-plugin) to make runtimes with JRE

## Notes

* Only attachments which have a "content ID" are considered, multipart messages (1 level deep) with subtypes should be correctly handled.
* Original messages end up in trash, gmail removes them periodically.
* IMAP message flags are preserved.
* Gmail message labels are preserved. If the `label` tag (see above) exists, it is removed after attachment removal (doesn't work for conversation view).
* The processed mails get a new message-ID, which doesn't matter.
* License: GPL
