# Gmail attachment remover

This is an app that connects to your gmail account and removes selected attachments, for instance for large emails, without skrewing up the thread display. Google does not want to support this.

* Before removing an attachment, a local full backup of the email is saved. But you should save your important attachments manually before!
* The removed attachment is replaced by a text-file attachment containing the original attachment filename and size.

## Safety

* I use it from time to time and have never lost emails / skrewed up the thread-display etc.
* You should backup gmail with, e.g., [gmvault](http://gmvault.org) I might pretty easily integrate a backup/restore solution, but gmvault is heavily tested.
* Mind that Gmail might always, without warning, change their imap implementation, which might break things.
* Of course I cannot take any responsibility if you loose emails etc...
* The source code is very simple, so just check it!

## Changelog / Status ##


# Getting started #

## Tutorial
To run it, you need java >= 1.8u40 from https://jdk8.java.net/download.html

Do this on gmail:

* Optional: assign a certain label to your emails where you want to remove the attachment(s), I use `removeattachments`
    * I make a filter for large emails to assign this automatically. I always save important attachments on disk!
* enable imap access in gmail's settings, best enable access to all folders.

On your computer:

* download binary version from [here](https://bitbucket.org/wolfgang/gmail-attachment-remover/downloads)
* run it, and adjust settings:
    * set gmail email adress
    * select folder for mail backups
    * `limit`: select a maximum number of emails processed, e.g., 100. After this you can check the emails if everything is fine, further the process might take very long and google might interrupt the connection from time to time.
    * `label`: gmail message label to be searched for, e.g. `removeattachments`. It is removed after attachment removal. If empty, this is ignored.
    * `gmail search`: use a "RAW gmail search term" to pre-filter emails. See the examples in the drop-down list and (here)[https://support.google.com/mail/answer/7190?hl=en] for information.
    * `minimum Attachment size`: Select a minimum size above which attachments will be considered. Important for multiple attachments in mails!
* press `Authenticate account` to perform OAuth2 authentification. A web browser window should open, make sure that you are logged in with the correct gmail account!
* press `Get emails`. The table should be populated with possible emails. This might take some time!
* Check the table and remove attachments (or whole emails) from the table if they should not be removed.
* press `Remove attachments` to start the removal procedure. This might take some time!

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
* [javamail](http://www.oracle.com/technetwork/java/javamail/index.html) for everything about IMAP and gmail
* [scalaj-http](https://github.com/scalaj/scalaj-http) for oauth communication
* [json-simple](https://code.google.com/p/json-simple/) for oauth parsing

# Details

* the settings file `sgarsettings.txt` is stored in the current working directory, usually next to the jar file. Its full path is displayed on startup
* only attachments which have a "filename" are considered. This excludes effectively html/text message parts.
* IMAP message flags are preserved during attachment removal, gmail seems to use only SEEN and FLAGGED (=starred).
* gmail message labels are preserved, too; if the `label` tag (see above) is non-empty, it is removed after attachment removal.
* the backup does NOT contain flags and labels. Could easily be added in gmvault-style.

# Contributors #

Bugreports, Comments and Contributions are of course very welcome, please use the standard methods or email.

# Maintainer #

wolfgang.loeffler@gmail.com
