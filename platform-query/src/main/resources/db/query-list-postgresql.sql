CREATE TABLE IF NOT EXISTS query_list_definition (
    list_key varchar(100) PRIMARY KEY,
    title varchar(200) NOT NULL,
    body text NOT NULL,
    enabled boolean NOT NULL,
    version bigint NOT NULL,
    modified_by varchar(200) NOT NULL,
    modified_at timestamp(6) with time zone NOT NULL
);
