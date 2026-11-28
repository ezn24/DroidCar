# Car replay fixtures

Extracted from real run logs by `tools/extract_car_fixtures.py` (`*.jsonl`) for `replay/BtLinkReplayTest` and
`replay/TunnelReplayTest`, the cross-car regression: after changing the protocol for one car, every other car's recorded
stimuli must still get the recorded answers.

```bash
python3 tools/extract_car_fixtures.py ~/logs/droidcar -o app/src/test/resources/carplay/fixtures
./gradlew testStandardDebugUnitTest --tests '*Replay*'
```

**`*.jsonl` is gitignored by default**: the files hold real data (head-unit Bluetooth addresses, hotspot names, pairing
public keys). To add one as a public regression sample, review and redact it file by file first (SSIDs may be renamed;
leave MAC addresses alone, the protocol checks them), then un-ignore it. Without fixtures both tests skip themselves
(fresh clones are not affected).

## Assertion tiers (decided at extraction, stored per round in the rxHasIdent field)

- The old RAW dump budget of 512 B per direction often cut the identification frame in half (Audi 575 B, Geely 489 B).
  Worse, truncation can **splice the tails of different reads into a fake frame of exactly the right length** (payload
  checksum fails and the engine drops it silently; found while debugging replays). The extractor checks every frame the
  way the engine does (header / payload sums to zero), cuts everything from the first bad frame and marks
  `rxHasIdent`; such rounds get **link-layer assertions** only (SYN / adopted session table / identification start
  0x1D00 must match the recording).
- Identification frame intact (`rxHasIdent=true`) -> **full assertions**: the recorded distinct CSM sequence must be an
  ordered prefix of the replay.
- Tunnel layer: the recorded sequence must be an **ordered subsequence** of the replay (allows deliberate additions such
  as the MIB3 burst, catches missing or reordered answers).
- The budget is now 4096 B (`Iap2LinkChannel.RAW_DUMP_LIMIT*`): new logs capture identification / MFi / credentials in
  full, and their fixtures get full assertions automatically.
