# Developer Workspace Agent demo website

The demo website is part of `agent-permit-playground`; it is not a third repository. It is the shared visual story for AgentPermit4j and RAGContract4j.

## Minimum screen

```text
+-----------------------------+--------------------------------+
| Conversation                | Execution timeline             |
|                             | tool selected                  |
| Ask about a production      | schema valid                   |
| database incident           | permission granted             |
|                             | risk assessed                  |
| Request a config change     | waiting for approval           |
|                             | executed once / denied          |
+-----------------------------+--------------------------------+
| Tool calls | Approvals | Audit | RAG trace | Policies | Replay|
+--------------------------------------------------------------+
```

## Two-minute golden path

1. Ask how to handle exhausted production database connections.
2. Display an authorized team Runbook and citations supplied by the RAG demo trace.
3. Run a read-only diagnostic SQL call automatically.
4. Request a production configuration change and pause for approval.
5. Try a prompt-injection message that claims approval; show that backend policy still blocks it.
6. Approve the exact normalized arguments and execute once.
7. Open the audit timeline and safe replay view.
8. Show a RAGContract4j CI fixture failing because a Team A user retrieved a Team B document.

## Approval detail contract

The UI must show tool, principal, resource, operation, risk level, stable reason codes, normalized arguments, fingerprint, expiry, and policy version. The backend remains the source of truth.

## Implementation order

1. [x] Static clickable wireframe using fixture JSON.
2. [x] Live AgentPermit4j decision/approval/audit APIs.
3. [ ] Import a `RagTrace` fixture from RAGContract4j.
4. [ ] Optional live RAG integration after both libraries have stable contracts.

The web UI lives in `agent-permit-playground/src/main/resources/webui`. A Java 21 loopback HTTP server exposes fixed synthetic cases through the production decision pipeline and serves live decision, approval, audit, and replay responses without contacting an external system. The RAG trace remains synthetic until step 3. No Node.js or frontend runtime is required.
