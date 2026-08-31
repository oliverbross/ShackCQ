# Morse training corpora

Offline trainer decks imported from the local MorseTrainerPro project on 2026-08-31:

- `data/words.txt` -> `words.txt`
- `data/abbreviations.txt` -> `abbreviations.txt`
- `data/callsigns.txt` -> `callsigns.txt`
- `data/qr-codes.txt` -> `qr-codes.txt`
- `data/top-words-in-cw.txt` -> `top-words-in-cw.txt`
- `MorseTrainerRufzProMobile/src/data/cwops.txt` -> `cwops.txt`

The Android repository uses reservoir sampling, so every deck remains available offline without loading the full file into memory.
