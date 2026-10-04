# Local prototype architecture (as built)

```mermaid
flowchart LR
  subgraph Device[Android app — offline, foreground-owned]
    UI[Compose UI and session library]
    Sensors[Android IMU and optional GNSS]
    Sim[Synthetic UI fixture]
    Acquire[Acquisition and canonical records]
    Recorder[Optional private recorder]
    Replay[Read-only local replay]
    Runtime[NavigationRuntime]
    Cal[Calibration engine]
    EKF[Fusion EKF and constraints]
    Presentation[Typed navigation presentation]
    Map[MapLibre renderer]
    Tiles[Bundled Hyderabad vector tiles]
    Graph[Bundled OSM road graph]
    Matcher[Opt-in map-matching comparison]
    Evaluation[Bundled host scripted evaluation report]

    Sensors --> Acquire
    Acquire --> Recorder
    Acquire --> Runtime
    Replay --> Runtime
    UI --> Sim
    Sim --> Map
    Runtime --> Cal
    Cal -. no ordinary app calibration hand-off .-> EKF
    EKF --> Presentation
    Presentation --> Map
    Tiles --> Map
    Presentation --> Matcher
    Graph --> Matcher
    Matcher -. parallel evaluation overlay only .-> Map
    Evaluation --> UI
    UI --> Acquire
    UI --> Recorder
    UI --> Replay
  end

  Contracts[Versioned Python/Kotlin contracts]
  Acquire -. typed records .-> Contracts
  Recorder -. frozen recording format .-> Contracts
  Replay -. strict read-only validation .-> Contracts
  Runtime -. navigation output .-> Contracts
  Research[Python tools, offline tests and experiment validators]
  Contracts --> Research
  Truth[Independent field reference — not available]
  Research -. blocked: no approved sealed field experiment .-> Truth
  Model[Approved model artifact — none; 0 approved sequences]
  Research -. training admission no-go .-> Model
```

## Data boundaries

- Android sensors/location feed the typed acquisition stream. Optional recording writes to
  private no-backup storage; export is a separate user-initiated local copy; replay reads
  existing sessions without rewriting them. All stop on lifecycle/background boundaries.
- The map displays exactly one selected source: synthetic UI, recorded raw GNSS, live raw
  GNSS, or navigation-engine output. Map rendering is not localization. Road matching is an
  optional, side-by-side comparison and does not overwrite navigation output.
- Calibration and fusion/constraints have host-tested implementations, but normal app flow
  provides no valid calibration hand-off. Thus a real calibrated navigation solution is not
  an implemented end-user capability.
- Python research/validation tools are offline and do not run in the Android app. The only
  checked-in evaluation report scores generated inputs against scripted truth; it is not
  independent field evidence. No trained model exists.
- There is no app server, account store, telemetry path, online map fallback, or runtime
  network permission.

See [capability matrix](PROTOTYPE_CAPABILITIES.md),
[local package procedures](../RELEASE_PACKAGE.md), and
[known limitations](../KNOWN_LIMITATIONS.md).
