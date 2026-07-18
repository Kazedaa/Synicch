# Synicch server

Indexer and API for the Synicch photo library. See the [root README](../README.md)
for architecture and setup.

    synicch init                 create directories and database
    synicch scan                 index new/changed files in the backup folder
    synicch scan --refresh       reprocess everything
    synicch status               library summary
    synicch settings             show settings
    synicch settings display_timezone America/Los_Angeles

Deployed to `/opt/synicch` on the server, running in a venv at
`/opt/synicch/venv`. Data lives in `/var/lib/synicch`.

Deploy with `SYNICCH_HOST=root@your-server ./deploy.sh`.
