# Introduction Gmail attachment remover

An app that connects to your gmail account and removes selected attachments, for instance for large emails. Google does not support this.

* Before removing an attachment, a local full backup of the email is saved.

## safety

* I use it from time to time and have never lost emails / skrewed up thread-display etc.
* You should backup gmail with, e.g., [gmvault](http://gmvault.org) I might pretty easily integrate a backup/restore solution, but gmvault is heavily tested.
* Mind that Gmail might always, without warning, change their "imap" implementation, which might break things.
* Of course I cannot take any responsibility if you loose emails etc...


## Changelog / Status ##

# Getting started #

## Tutorial
To run it, you need java >= 1.8

Do this on gmail:

* assign a certain label to your emails where you want to remove the attachment(s), I use `removeattachments`
    * I make a filter for large emails to assign this automatically. I always save important attachments on disk!
* enable imap access TODO

On your computer:

* download binary version from TODO
* run it, and adjust settings:
    * adjust user name (email adress), password, folder for backups
    * `limit`: select a maximum number of emails processed, e.g., 100. After this you can check the emails if everything is fine, further the process might take very long.
    * `gmail search`: use a "RAW gmail search term" to pre-filter emails. See the proposed examples in the drop-down list.
    * `minimum Attachment size`: Select a minimum size above which attachments will be considered. Important for multiple attachments in mails!
* press `Connect`. A list of all your email folders on gmail should appear in the log window
* press `Get emails`. The table should be populated with possible emails.
* Check the table and remove attachments / emails (to preserve all attachments in an email) from the table if they should not be removed
* press `Remove attachments` to start the removal procedure

TODO

## Run from source:

To build it, you need a java jdk >= 1.8 and sbt 0.13.

Do simply:

    sbt run


# Used frameworks #

* [scala](http://scala-lang.org) 2.11.4
* [scalafx](http://www.scalafx.org)

TODO

# Contributors #

Contributions are of course very welcome, please contact me or use the standard methods.

# Maintainer #

wolfgang.loeffler@gmail.com