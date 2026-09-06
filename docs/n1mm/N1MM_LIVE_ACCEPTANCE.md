# N1MM live acceptance

Not performed in this branch. Automated loopback proves codecs, discovery/TCP framing, heartbeat, explicit start/stop and policy—not real N1MM+ interoperability.

Use a disposable N1MM+ test database and isolated trusted LAN:

1. Confirm the exact N1MM+ version and matching contest/rule identity; leave ShackCQ non-master and monitor-only.
2. Verify trailing-percent discovery, paired TCP links, hello/status, ECHO liveness, disconnect and reconnect without opening non-selected interfaces or VPN broadcast.
3. Compare every command/43-field contact/XML fixture against observed traffic without enabling radio/TX/control actions.
4. In monitor mode, prove add/edit/delete/checksum traffic cannot mutate the canonical log.
5. Explicitly trust one peer; accept one unambiguous add and prove canonical QSO, contest link and existing Wavelog outbox behaviour.
6. Prove replay/paired-link/local-echo dedupe with three stations; prove distinct contacts are retained.
7. Confirm edits, deletes, checksum repair and serial conflicts remain review items.
8. Attack-test malformed discovery, advertised-IP mismatch, oversized/incomplete frames, malformed UTF-8/XML, flood, reconnect loop, `TIME`, `FILE`, path traversal and control commands.
9. Stop networking, kill/restart the app and prove no sockets reopen.

Record packet-independent counters and screenshots without raw private QSO data/IPs. Only after this run may documentation add a version-specific live interoperability claim; it must still not claim N1MM+ certification.
