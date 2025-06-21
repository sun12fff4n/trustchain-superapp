## FROST Threshold Signature Documentation

### 1. Introduction

FROST (Flexible Round-Optimized Schnorr Threshold) is a threshold signature scheme enabling distributed signing among multiple parties. This implementation integrates FROST with:

- **TrustChain**: For peer-to-peer messaging and data persistence  
- **Bitcoin**: For transaction creation and signature verification  
- **Raft Consensus(TBD)**: For coordinator election and fault tolerance

**Key Features:**

- Distributed Key Generation (DKG)  
- Non-interactive Signing  
- t-of-n Threshold Fault Tolerance  
- Coordinator-Optimized Signing Sessions

### 2. System Architecture
#### 2.1 Component Diagram
```mermaid
graph TD
    A[FROST Engines] --> B[TrustChain Network]
    A --> C[Raft Consensus]
    A --> D[Bitcoin Network]
    B --> E[Message Persistence]
    C --> F[Leader Election]
    D --> G[Transaction Verification]
```

#### 2.2 Core Components

- **FrostKeyGenEngine**: Manages distributed key generation  
  - Polynomial generation  
  - Commitment broadcasting  
  - Share verification

- **FrostPreProcessingEngine**: Handles nonce preparation  
  - Pre-computes signing nonces  
  - Stores commitment data

- **FrostSigningEngine**: Orchestrates threshold signing  
  - Collects partial signatures  
  - Aggregates final signature  
  - Broadcasts completed signature

- **CoinCommunity**: TrustChain integration layer  
  - Message serialization and parsing  
  - Blockchain communication  
  - Peer discovery and networking

#### 2.3 System Overview

```mermaid
sequenceDiagram
    participant P as Participant
    participant C as CoinCommunity
    participant T as TrustChain
    participant B as Bitcoin
    
    P->>C: createBitcoinGenesisWallet()
    C->>T: Create SWJoinBlockTransaction
    T-->>C: Block created
    C->>C: Initialize FROST engines
    C->>T: Broadcast DKG messages
    C->>T: Broadcast nonce commitments
    
    par Parallel Processes
        C->>C: Monitor DKG messages
        C->>C: Monitor nonce messages
    end
    
    P->>C: proposeJoinWalletFrost()
    C->>T: Create FrostSignatureAskTransaction
    C->>T: Create FrostBroadcastingTransaction (join proposal)
    
    loop Signature Collection
        C->>T: Query responses
        T-->>C: Return partial signatures
        C->>C: Aggregate signatures
    end
    
    C->>B: Submit final transaction
    B-->>C: Transaction confirmation
    C->>T: Create SWJoinBlockTransaction (new member)
```


### 3. Key Generation Process (DKG)
#### 3.1 Protocol Flow

```mermaid
sequenceDiagram
    participant P as Participant
    participant T as TrustChain
    participant C as Coordinator
    
    P->>P: Generate polynomial coefficients
    P->>T: Broadcast commitment (FrostBroadcastingTransaction)
    P->>T: Broadcast secret shares
    loop Periodic Check
        P->>T: Query new messages
        T->>P: Return DKG messages
    end
    P->>P: Compute group public key
    P->>C: Send verification share
    C->>P: Broadcast group public key
```

#### 3.2 Critical Code Snippets

​​Polynomial Generation​​:

```kotlin
// Sample polynomial coefficients
a = List(threshold) { randomZp() } 

// Compute commitments
commitment = a.map { coeff -> 
    FrostConstants.g.modPow(coeff, FrostConstants.p) 
}
```

​Zero-Knowledge Proof​​:

```kotlin
val k = randomZp()
val r = FrostConstants.g.modPow(k, FrostConstants.p)
val c = hashToBigInt("FROST-KeyGen", g_ai0, r, FrostConstants.n)
val z = k.add(ai0.multiply(c)).mod(FrostConstants.n)
proof = Pair(r, z)
```

### 4. Pre-processing Phase
#### 4.1 Nonce Generation Workflow

```mermaid
graph TD
    A[Start] --> B[Generate π nonce pairs]
    B --> C[Compute D = g^d, E = g^e]
    C --> D[Store nonces locally]
    D --> E[Broadcast to Signing Authority]
    E --> F[End]
```

#### 4.2 Code Implementation
```kotlin
fun generate() {
    Li.clear()
    for (j in 1..pi) {
        val dij = randomZp()
        val eij = randomZp()
        val Dij = FrostConstants.g.modPow(dij, FrostConstants.p)
        val Eij = FrostConstants.g.modPow(eij, FrostConstants.p)
        Li.add(Pair(Dij, Eij))
    }
    broadcast(FrostNoncesToSAMessage(...))
}
```

### 5. Threshold Signing Protocol
#### 5.1 Signing Sequence

```mermaid
sequenceDiagram
    participant SA as Signing Authority
    participant P as Participant
    participant T as TrustChain
    
    SA->>T: Broadcast signing request
    P->>T: Submit partial signature
    loop Until threshold
        SA->>T: Check responses
        T->>SA: Return signatures
        SA->>SA: Aggregate signatures
    end
    SA->>T: Broadcast final signature
```

#### 5.2 Signature Aggregation

```kotlin
fun onReceivedZi(participantIndex: Int, z_i: BigInteger) {
    collectedZi[participantIndex] = z_i
    finalSignature += z_i
    
    if (collectedZi.size * 100 >= threshold * storedNonces.size) {
        broadcast(FrostSigningResponseToJoinerMessage(...))
    }
}
```

### 6. TrustChain Integration
#### 6.1 Message Types

| Message Type                        | Purpose                            |
| ----------------------------------- | ---------------------------------- |
| `FrostBroadcastingTransaction`      | DKG commitments and shares         |
| `FrostSignatureAskTransaction`      | Signing request initiation         |
| `FrostResponseSignatureTransaction` | Partial signature submission       |
| `SWJoinBlockTransaction`            | Wallet creation and initialization |

#### 6.2 Message Handling

```kotlin
fun onFrostMessage(packet: Packet) {
    val payload = deserialize(packet)
    when (payload.messageType) {
        COMMITMENT -> processCommitment(...)
        VERIFICATION_SHARE -> processShare(...)
        // ... other types
    }
}
```


### 7. Security Mechanisms
#### 7.1 Cryptographic Guarantees


- ​​Verifiable Secret Sharing​​: Feldman VSS for share verification
- ​Binding Signatures​​: Prevents rogue key attacks
- Zero-Knowledge Proofs​​: Commitment verification

#### 7.2 Code-Level Protections

```kotlin
// Verifiable commitment check
fun verifyCommitment(commitment: List<BigInteger>, proof: Pair<BigInteger, BigInteger>): Boolean {
    val (r, z) = proof
    val c = hashToBigInt(...)
    val lhs = FrostConstants.g.modPow(z, p)
    val rhs = r.multiply(commitment[0].modPow(c, p)).mod(p)
    return lhs == rhs
}
```


### 10. Conclusion

This implementation offers a  FROST threshold signature system, with:

- Full DKG and signing workflows  
- TrustChain-based message relay and persistence  
- Bitcoin integration for real transaction signing

**Planned Improvements:**

- Raft-assisted coordination  
- Proactive secret resharing  
- Multi-chain compatibility  
- Formal security verification  
- Mobile-optimized client performance



