HTTP Service Monitor
====================

A simple tool that measures that your servers are reachable. If they are not,
the tool creates a repeating alarm with flashing indicator light and alarm sound
until the notification is dismissed.

Poll interval is hardcoded to 10 minutes.

OK status is considered to be HTTP response code of 200. Other statuses trigger
FAIL state. On first entry to FAIL state, an error notification may be generated.

Why this, why not nagios or some shit?
--------------------------------------

Well, a modern smartphone is a fine server. ;-) I like that it has no other
dependencies outside itself.
