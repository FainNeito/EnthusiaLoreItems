# Handoff 0040 - protection fixture client synchronization

- PR61, codex/per-item-storage, authoritative wsg138/EnthusiaLoreItems. Prior head ab5bced19893b7995ab36ad37643b55505d1e3cd passed Codacy, hosted shulker runtime and test JAR build; remaining hosted gates were running.
- Hosted protection job 114393797312 failed at `drop sword missing voiddrop`. Artifact 11692149563 shows the database had recorded direct delivery immediately before the bot failed to see the sword in its inventory. No plugin exception was logged. This is evidence of a fixture delivery/client synchronization race, not proof of a storage regression.
- Connector retry was denied with 403 Resource not accessible by integration. Added a bounded 15-second bot wait for the sword before dropping it; missing items still fail and all lifecycle/protection assertions remain unchanged. No plugin behavior changes in this checkpoint.
- Inspect final-head hosted checks, particularly protection-contract. Native Java/Bedrock acceptance for the new storage policy remains pending. No merge, deployment or restart authorized/performed. Reports 0037-0039 retain source/local verification evidence.
