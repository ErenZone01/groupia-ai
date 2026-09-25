--liquibase formatted sql

--changeset groupia:001-modulith-event-publication dbms:postgresql
--comment: Technical publication registry required by Spring Modulith JPA 2.1.1; no business tables.
CREATE TABLE event_publication (
    id UUID PRIMARY KEY,
    publication_date TIMESTAMP WITH TIME ZONE NOT NULL,
    listener_id TEXT NOT NULL,
    serialized_event TEXT NOT NULL,
    event_type TEXT NOT NULL,
    completion_date TIMESTAMP WITH TIME ZONE,
    last_resubmission_date TIMESTAMP WITH TIME ZONE,
    completion_attempts INTEGER NOT NULL,
    status TEXT
);

CREATE INDEX event_publication_completion_date_idx
    ON event_publication (completion_date);

--rollback DROP TABLE event_publication;
