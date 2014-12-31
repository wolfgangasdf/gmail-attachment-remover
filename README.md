# Introduction Gmail attachment remover

This is an app that connects to your gmail account and removes selected attachments, for instance for large emails, without skrewing up the thread display. Google does not want to support this.

* Before removing an attachment, a local full backup of the email is saved. But you should save your important attachments manually before!
* The removed attachment is replaced by a text-file attachment containing the original filename and size

## Safety

* I use it from time to time and have never lost emails / skrewed up the thread-display etc.
* You should backup gmail with, e.g., [gmvault](http://gmvault.org) I might pretty easily integrate a backup/restore solution, but gmvault is heavily tested.
* Mind that Gmail might always, without warning, change their imap implementation, which might break things.
* Of course I cannot take any responsibility if you loose emails etc...
* The source code is very simple, so just check it!

## Changelog / Status ##

* Missing: OAuth2 authentification

# Getting started #

## Tutorial
To run it, you need java >= 1.8

Do this on gmail:

* Optional: assign a certain label to your emails where you want to remove the attachment(s), I use `removeattachments`
    * I make a filter for large emails to assign this automatically. I always save important attachments on disk!
* enable imap access in gmail's settings, best enable access to all folders.
* enable imap login. Currently, only password-based login is supported. Either enable "less-secure login" or create an "application-specific password" if you use gmail's two-stage authentification. I will add OAuth2 soon.

On your computer:

* download binary version from [here](https://bitbucket.org/wolfgang/gmail-attachment-remover/downloads)
* run it, and adjust settings:
    * adjust user name (email adress), password, folder for backups
    * `limit`: select a maximum number of emails processed, e.g., 100. After this you can check the emails if everything is fine, further the process might take very long and google might interrupt the connection from time to time.
    * `label`: gmail message label to be searched for, e.g. `removeattachments`. It is removed after attachment removal. If empty, this is ignored.
    * `gmail search`: use a "RAW gmail search term" to pre-filter emails. See the examples in the drop-down list and (here)[https://support.google.com/mail/answer/7190?hl=en] for information.
    * `minimum Attachment size`: Select a minimum size above which attachments will be considered. Important for multiple attachments in mails!
* press `Connect`. A list of all your email folders on gmail should appear in the log window
* press `Get emails`. The table should be populated with possible emails. This might take some time!
* Check the table and remove attachments / emails (to preserve all attachments in an email) from the table if they should not be removed. This might take some time!
* press `Remove attachments` to start the removal procedure

## Run from source:

To build it, you need a java jdk >= 1.8 and sbt 0.13.

Do simply:

    sbt run


## package application

    sbt packageJavafx

# Used frameworks #

* [scala](http://scala-lang.org) 2.11.4
* [scalafx](http://www.scalafx.org) as wrapper for javafx
* [sbt-javafx](https://github.com/kavedaa/sbt-javafx) for packaging
* [javamail](http://www.oracle.com/technetwork/java/javamail/index.html) for everything regarding IMAP and gmail


# Details

* the settings file `sgarsettings.txt` is stored in the current working directory, usually next to the jar file.
* only attachments which have a "filename" are considered. This excludes effectively html/text message parts.
* IMAP message flags are preserved during attachment removal, gmail seems to use only SEEN and FLAGGED (=starred).
* gmail message labels are preserved, too; if the `label` tag (see above) is non-empty, it is removed after attachment removal.
* the backup does NOT contain flags and labels. Could easily be added in gmvault-style.

# Contributors #

Contributions are of course very welcome, please contact me or use the standard methods or email.

# Maintainer #

wolfgang.loeffler@gmail.com
