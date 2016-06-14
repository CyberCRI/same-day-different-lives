DROP TABLE IF EXISTS groups;
CREATE TABLE groups (
	group_id char(7) PRIMARY KEY,
	name varchar(255),
	created_at timestamp default current_timestamp
);

DROP TABLE IF EXISTS responses;
CREATE TABLE responses (
	response_id serial primary key,
	group_id char(7) references groups(group_id),
	created_at timestamp default current_timestamp
);

DROP TABLE IF EXISTS phrases;
CREATE TABLE phrases (
	response_id int references responses(response_id),
	phrase varchar(140)
);
CREATE INDEX phrase_index ON phrases (phrase);
