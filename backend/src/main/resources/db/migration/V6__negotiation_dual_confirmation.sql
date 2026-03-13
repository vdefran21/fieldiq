-- V6: Dual confirmation support for negotiation sessions.
-- See docs/specs/phase-1/04-negotiation-protocol.md line 52:
-- "Both managers confirm -> status = 'confirmed'"
-- Events are only created after both sides confirm independently.
-- This prevents one-sided scheduled games if the second manager never confirms.

ALTER TABLE negotiation_sessions
    ADD COLUMN initiator_confirmed BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN responder_confirmed BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN agreed_ends_at TIMESTAMPTZ;
