CREATE TABLE author (id bigserial PRIMARY KEY, name text NOT NULL);
CREATE TABLE book (id bigserial PRIMARY KEY, author_id bigint REFERENCES author(id), title varchar(100) NOT NULL);
INSERT INTO author(name) VALUES ('Leskov');
