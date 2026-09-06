# Official microHAM ARCO source audit

Reviewed 2026-08-23 from the official ARCO product and downloads pages and the English manual. Manual: v4.1 / 2024, downloads date 2024-12-10, URL `https://www.microham.com/Downloads/ARCO_English_Manual.pdf`, SHA-256 `daa5084ac5034c65b0bfb5f19a0e339ac26429624d511043ec186d21e8bd17b4`. The copyrighted PDF is not committed; its cover states microHAM copyright and all rights reserved.

Current firmware: 4.2.B, released 2025-01-03. Firmware SHA-256: `541649ed3c429205d7054d6c2cb6fc9f09ba2448aa72132d697671b8714589fc`. Change-log SHA-256: `69dad8ea98ebde86f86276812d6213b3f7fa598d944acf39e5291bbcea957e92`.

Reviewed manual sections: Rotator Settings/Link, Heading Calibration/Antennas, LAN, Internet Remote Control, System, USB Firmware Update, and USB Serial Port. Confirmed RS-232, USB CDC serial (VID 0483 / PID a2f7), Ethernet, azimuth/elevation use, up to four LAN connections, Yaesu GS-232A, DCU-1/Rotor-EZ, SPID HR 0.1-degree and native protocol selection, offsets, bidirectional antennas, LINK installations, and controller protections.

ShackCQ uses only documented external compatibility protocols. It does not implement unpublished native-ARCO framing, VNC, ARXC relay control, firmware update, calibration/reset, motor/mains engineering, or LINK-bus controls. Because the manual does not publish native-ARCO or SPID-HR framing, those are explicit live/source blockers; ARCO profiles use reviewed GS-232A or DCU/Rotor-EZ framing until authoritative protocol documentation is available. `HeadingOffsetOwner` prevents duplicate controller and ShackCQ offsets.
