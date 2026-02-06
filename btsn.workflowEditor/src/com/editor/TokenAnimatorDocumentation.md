# Token Animator System Documentation

## Overview

The Token Animator system visualizes token flow through a Petri net workflow. It parses analyzer output, builds a topology from the canvas, pre-computes animation segments, and provides state queries for smooth rendering.

## Package Structure

```
com.editor/
+-- TokenAnimator.java         # Main animation engine (3065 lines)
+-- Canvas.java                # Rendering surface
+-- AnimationControlPanel.java # Playback UI controls
¦
+-- animator/                  # Data classes
    +-- Phase.java             # Animation phase enum
    +-- AnimationSegment.java  # Pre-computed segment
    +-- MarkingEvent.java      # Parsed event from analyzer
    +-- TokenAnimState.java    # Current render state
    +-- BufferedToken.java     # Token waiting at T_in
    +-- AnimationSnapshot.java # Complete state at time T
    +-- AnimationConstants.java # Timing constants
```

---

## Core Concepts

### Petri Net Elements

| Element | Description | Naming Convention |
|---------|-------------|-------------------|
| **Place** | Service/location where tokens reside | `P1_Place`, `serviceName` |
| **T_in** | Input transition (entry to place) | `T_in_ServiceName` |
| **T_out** | Output transition (exit from place) | `T_out_ServiceName` |
| **EventGenerator** | Creates tokens | `EG_name` |
| **Terminate** | Consumes tokens (workflow end) | `Terminate_name` |

### Token Flow

```
EventGenerator ? T_in ? Place ? T_out ? T_in ? Place ? ... ? Terminate
                  ?                       ¦
                  +-- buffer if blocked --+
```

### Token ID Convention

- **Parent tokens**: `1000000`, `1010000`, `1020000` (divisible by 10000)
- **Child tokens**: `1030001`, `1030002` (parent ID + child suffix)

---

## Data Classes (`com.editor.animator`)

### Phase (enum)

Token animation phases in lifecycle order:

```java
public enum Phase {
    AT_EVENT_GENERATOR,      // Just created, visible at event generator
    TRAVELING_TO_TIN,        // Moving from EG/T_out to T_in
    BUFFERED_AT_TIN,         // Waiting in buffer at T_in (blocked)
    TRAVELING_TO_PLACE,      // Moving from T_in into place
    AT_PLACE,                // Stationary inside place
    TRAVELING_TO_TOUT,       // Moving from place to T_out
    TRAVELING_TO_NEXT_TIN,   // Moving between T_out and next T_in
    TRAVELING_TO_TERMINATE,  // Moving to terminate node
    AT_TERMINATE,            // Arrived at terminate (brief)
    CONSUMED                 // Animation complete
}
```

### AnimationSegment

Pre-computed animation segment representing a token in a specific state for a time range.

```java
public class AnimationSegment {
    public final String tokenId;      // e.g., "1000000"
    public final String version;      // e.g., "v001"
    public final Phase phase;         // Current phase
    public final long startTime;      // Segment start (ms)
    public final long endTime;        // Segment end (ms)
    
    // Location context (depends on phase)
    public final String placeId;      // Place service name
    public final String tInId;        // T_in transition label
    public final String tOutId;       // T_out transition label
    public final String fromElement;  // Element traveling FROM
    public final String toElement;    // Element traveling TO
    public final String terminateId;  // Terminate node (if applicable)
    public final String eventGenId;   // Event generator (if applicable)
    
    // Methods
    public double getProgress(long time);  // 0.0 to 1.0
    public boolean isActiveAt(long time);  // startTime <= time < endTime
}
```

### MarkingEvent

Parsed event from analyzer output.

```java
public class MarkingEvent implements Comparable<MarkingEvent> {
    public final long timestamp;
    public final String tokenId;
    public final String version;
    public final String placeId;
    public final boolean entering;
    public final int buffer;
    public final String toPlace;
    public final String eventType;
    public final String transitionId;
    
    // Type queries
    public boolean isFork();
    public boolean isEnter();
    public boolean isExit();
    public boolean isBuffered();
    public boolean isJoinConsumed();
    public boolean isJoinSurvivor();
    public boolean isTerminate();
    public boolean isForkConsumed();
    public boolean isGenerated();
}
```

**Event Types:**
| Type | Description |
|------|-------------|
| `ENTER` | Token enters a place |
| `EXIT` | Token leaves a place |
| `FORK` | Child token created |
| `BUFFERED` | Token waiting at JOIN |
| `JOIN_CONSUMED` | Child token consumed by JOIN |
| `JOIN_SURVIVOR` | Token survives JOIN (continues) |
| `TERMINATE` | Token exits workflow |
| `GENERATED` | Token created by EventGenerator |

### TokenAnimState

Current render state for Canvas.

```java
public class TokenAnimState {
    public String tokenId;
    public String version;
    public Phase phase;
    public String currentPlaceId;
    public String eventGenId;
    public String tInId;
    public String tOutId;
    public String nextTInId;
    public String terminateNodeId;
    public String fromElementId;
    public String toElementId;
    public long phaseStartTime;
    public long phaseEndTime;
    public double progress;        // 0.0 to 1.0 for interpolation
    public int bufferPosition;     // Position in buffer queue
}
```

### BufferedToken

Token waiting in a buffer at T_in.

```java
public class BufferedToken {
    public final String tokenId;
    public final String version;
    public final long arrivalTime;
}
```

### AnimationSnapshot

Complete animation state at a point in time.

```java
public class AnimationSnapshot {
    public final Map<String, TokenAnimState> tokenStates;
    public final Map<String, List<BufferedToken>> bufferStates;
}
```

### AnimationConstants

Timing configuration.

```java
public final class AnimationConstants {
    public static final double VELOCITY = 0.5;              // pixels per ms
    public static final long MIN_TRAVEL_DURATION = 50;      // ms
    public static final long TIME_AT_EVENT_GEN = 300;       // ms
    
    // Fallback durations when positions unavailable
    public static final long FALLBACK_TRAVEL_DURATION_EG_TO_TIN = 150;
    public static final long FALLBACK_TRAVEL_DURATION_TO_PLACE = 120;
    public static final long FALLBACK_TRAVEL_DURATION_TO_TOUT = 80;
    public static final long FALLBACK_TRAVEL_DURATION_TO_NEXT = 300;
}
```

---

## TokenAnimator

Main animation engine. Manages topology, parses events, generates segments, and provides state queries.

### Lifecycle

```
1. new TokenAnimator()
2. setCanvas(canvas)
3. buildTopologyFromCanvas()    ? Extracts Petri net structure
4. parseAnalyzerOutput(text)    ? Parses events + generates segments
5. getTokenStatesAt(time)       ? Called every frame for rendering
```

### Key Public Methods

#### Setup

```java
void setCanvas(Canvas canvas)
void buildTopologyFromCanvas()
void parseAnalyzerOutput(String text)
void clear()
```

#### State Queries (called during animation)

```java
Map<String, TokenAnimState> getTokenStatesAt(long time)
Map<String, List<BufferedToken>> getBufferStatesAt(long time)
AnimationSnapshot getAnimationSnapshotAt(long time)
```

#### Timing

```java
long getStartTime()
long getEndTime()
long getDuration()
boolean hasData()
boolean hasTopology()
```

#### Topology Queries

```java
String getTInForPlace(String placeId)
String getTOutForPlace(String placeId)
String getNextTInForTOut(String tOutId)
String getPlaceForTIn(String tInId)
boolean isFork(String tOutId)
boolean isJoin(String tInId)
boolean leadsToTerminate(String tOutId)
Set<String> getPlaceIds()
Set<String> getTInIds()
Set<String> getEventGeneratorIds()
```

#### Version Colors

```java
Color getVersionColor(String version)
Set<String> getVersions()
```

#### Debugging

```java
void printSegments()
void printTopology()
```

### Internal Structure

| Section | Lines | Purpose |
|---------|-------|---------|
| Topology maps | 52-77 | Store Petri net structure |
| Data storage | 79-87 | Events, segments, colors |
| Distance calculation | 108-207 | Euclidean path distances |
| Topology building | 209-421 | Extract structure from Canvas |
| Parsing | 423-592 | Parse analyzer output |
| Segment generation | 594-2785 | Pre-compute animation segments |
| State retrieval | 2787-2875 | Query state at time T |
| Utilities | 2876-2907 | Helper methods |
| Getters | 2908-3065 | Public accessors |

---

## Animation Flow

### 1. Topology Building

`buildTopologyFromCanvas()` extracts:
- Place ? T_in ? T_out relationships
- Event generators and their target T_ins
- Fork/Join detection
- Terminate nodes
- Element positions for distance calculations

### 2. Event Parsing

`parseAnalyzerOutput()`:
1. Parses timestamped events from analyzer text
2. Groups events by token ID
3. Sorts by timestamp

### 3. Segment Generation

Two-pass process:

**Pass 1: Generate raw segments**
- For each token, walk through events
- Create segments for each phase transition
- Handle FORK, JOIN, TERMINATE specially

**Pass 2: Synchronize fork/join points**
- Align parent/child token timing at forks
- Ensure children arrive at JOIN simultaneously
- Resolve overlapping place occupancy

### 4. Rendering

Each frame, `AnimationControlPanel` calls:
```java
Map<String, TokenAnimState> states = animator.getTokenStatesAt(currentTime);
Map<String, List<BufferedToken>> buffers = animator.getBufferStatesAt(currentTime);
canvas.setAnimationState(states, colors, buffers);
```

Canvas uses `TokenAnimState.progress` to interpolate positions.

---

## Fork/Join Handling

### Fork (one token becomes many)

```
Parent token at T_out
        ¦
        ?
   +---------+
   ?         ?
Child 1   Child 2
```

- Parent receives `EXIT` event
- Children receive `FORK` events at same timestamp
- Animation shows split at T_out

### Join (many tokens become one)

```
Child 1   Child 2
   ¦         ¦
   ?         ?
   +---------+
        ¦
        ?
  Survivor token
```

- Children arrive at same T_in
- First arrivals get `BUFFERED` events
- Last arrival triggers JOIN
- One child gets `JOIN_SURVIVOR`, others get `JOIN_CONSUMED`
- Consumed tokens fade out

---

## Buffer Visualization

When tokens wait at a T_in (blocked by place occupancy or JOIN synchronization):

1. Token enters `BUFFERED_AT_TIN` phase
2. `BufferedToken` added to buffer list for that T_in
3. Canvas renders buffer queue near T_in
4. When unblocked, token transitions to `TRAVELING_TO_PLACE`

---

## Distance-Based Timing

Animation speed is consistent regardless of path length:

```java
duration = distance / VELOCITY
```

Where:
- `distance` = Euclidean distance (or path with waypoints)
- `VELOCITY` = 0.5 pixels/ms (configurable in AnimationConstants)

Fallback durations used when element positions unavailable.

---

## Debugging

### Print topology
```java
animator.printTopology();
```

Output:
```
T_in -> Place: {T_in_Service1=P1_Place, ...}
T_out -> Terminate: {T_out_Service3=Terminate_1}
EventGen -> T_in: {EG_Start=T_in_Service1}
Implicit join T_ins: [T_in_Join]
Explicit join T_ins (JoinNode): [T_in_Sync]
```

### Print segments
```java
animator.printSegments();
```

Output:
```
Segment[1000000 AT_EVENT_GENERATOR 0-300 null->null place=null]
Segment[1000000 TRAVELING_TO_TIN 300-450 EG_Start->T_in_Service1 place=null]
Segment[1000000 TRAVELING_TO_PLACE 450-570 T_in_Service1->P1_Place place=P1_Place]
...
```

---

## Class Diagram

```
+-------------------------------------------------------------+
¦                    AnimationControlPanel                     ¦
¦  - animator: TokenAnimator                                   ¦
¦  - canvas: Canvas                                            ¦
¦  - currentTime: long                                         ¦
¦  + updateAnimationDisplay()                                  ¦
+-------------------------------------------------------------+
                      ¦ uses
                      ?
+-------------------------------------------------------------+
¦                      TokenAnimator                           ¦
¦  - events: List<MarkingEvent>                               ¦
¦  - segments: List<AnimationSegment>                         ¦
¦  - topology maps...                                          ¦
¦  + buildTopologyFromCanvas()                                 ¦
¦  + parseAnalyzerOutput(text)                                 ¦
¦  + getTokenStatesAt(time): Map<String, TokenAnimState>      ¦
¦  + getBufferStatesAt(time): Map<String, List<BufferedToken>>¦
+-------------------------------------------------------------+
                      ¦ produces
                      ?
+-------------------------------------------------------------+
¦                        Canvas                                ¦
¦  - animatedTokenStates: Map<String, TokenAnimState>         ¦
¦  - tInBufferStates: Map<String, List<BufferedToken>>        ¦
¦  + setAnimationState(states, colors, bufferStates)          ¦
¦  + paintComponent(g) ? draws tokens                         ¦
+-------------------------------------------------------------+

                    com.editor.animator
+---------------+ +---------------+ +-----------------------+
¦    Phase      ¦ ¦MarkingEvent   ¦ ¦  AnimationSegment     ¦
¦  (enum)       ¦ ¦ - timestamp   ¦ ¦  - tokenId, phase     ¦
¦               ¦ ¦ - tokenId     ¦ ¦  - startTime, endTime ¦
¦ AT_EVENT_GEN  ¦ ¦ - eventType   ¦ ¦  - from/to elements   ¦
¦ TRAVELING_... ¦ ¦ + isFork()    ¦ ¦  + getProgress(time)  ¦
¦ BUFFERED_...  ¦ ¦ + isJoin()    ¦ ¦                       ¦
¦ AT_PLACE      ¦ ¦ + isEnter()   ¦ ¦                       ¦
¦ ...           ¦ ¦               ¦ ¦                       ¦
+---------------+ +---------------+ +-----------------------+

+---------------+ +---------------+ +-----------------------+
¦TokenAnimState ¦ ¦BufferedToken  ¦ ¦ AnimationConstants    ¦
¦ - phase       ¦ ¦ - tokenId     ¦ ¦  VELOCITY = 0.5       ¦
¦ - progress    ¦ ¦ - arrivalTime ¦ ¦  MIN_TRAVEL_DURATION  ¦
¦ - fromElement ¦ ¦               ¦ ¦  TIME_AT_EVENT_GEN    ¦
¦ - toElement   ¦ ¦               ¦ ¦  FALLBACK_TRAVEL_...  ¦
+---------------+ +---------------+ +-----------------------+
```

---

## Future Refactoring Opportunities

The following could be extracted from TokenAnimator:

| Component | ~Lines | Risk |
|-----------|--------|------|
| TopologyBuilder | 300 | Medium - many edge cases |
| EventParser | 200 | Low |
| SegmentGenerator | 1500 | High - complex fork/join logic |
| StateRetriever | 200 | Low |

Recommendation: Only refactor with comprehensive test coverage.