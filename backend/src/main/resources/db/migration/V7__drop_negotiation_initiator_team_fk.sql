-- Drop the local FK on negotiation_sessions.initiator_team_id so responder-side
-- shadow sessions can store the initiator's remote team UUID without requiring a
-- duplicate local team row.
-- See docs/specs/phase-1/04-negotiation-protocol.md and docs/specs/phase-1/01-schema.md

ALTER TABLE negotiation_sessions
    DROP CONSTRAINT IF EXISTS negotiation_sessions_initiator_team_id_fkey;
