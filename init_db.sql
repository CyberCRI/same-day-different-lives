DROP TYPE IF EXISTS challenge_type CASCADE;
CREATE TYPE challenge_type AS ENUM ('image', 'audio');

DROP TABLE IF EXISTS challenges CASCADE;
CREATE TABLE challenges (
	challenge_id serial primary key,
	type challenge_type,
	description text
);
INSERT INTO challenges (type, description) VALUES
	('image', 'Take a photo of your breakfast'),
	('image', 'Take a photo of your favorite piece of clothing'),
	('audio', 'What is the first memory that you have of your parents?'),
	('audio', 'What is worrying you the most right now?'),
	('image', 'Take a photo of something that makes you happy'),
	('audio', 'Tell about something that has changed a lot recently'),
	('audio', 'What was the scariest moment of your life?')
;

DROP TYPE IF EXISTS user_status CASCADE;
CREATE TYPE user_status AS ENUM ('dormant', 'ready', 'playing', 'banned');

DROP TABLE IF EXISTS users CASCADE;
CREATE TABLE users (
	user_id serial primary key,
	pseudo text,
	email text unique,
	password text, -- encrypted
	status user_status default 'dormant',
	created_at timestamp default current_timestamp
	-- TODO: demographic information
);

DROP TABLE IF EXISTS matches CASCADE;
CREATE TABLE matches (
	match_id serial primary key,
	user_a int references users(user_id),
	user_b int references users(user_id),
	created_at timestamp default current_timestamp,
	starts_at timestamp,
	ends_at timestamp,
	running boolean default TRUE
);

DROP TYPE IF EXISTS challenge_instance_status CASCADE;
CREATE TYPE challenge_instance_status AS ENUM ('upcoming', 'active', 'over');

DROP TABLE IF EXISTS challenge_instances CASCADE;
CREATE TABLE challenge_instances (
	challenge_instance_id serial primary key,
	challenge_id int references challenges(challenge_id),
	match_id int references matches(match_id),
	created_at timestamp default current_timestamp,
	starts_at timestamp,
	ends_at timestamp,
	status challenge_instance_status default 'upcoming'
);

DROP TABLE IF EXISTS challenge_responses CASCADE;
CREATE TABLE challenge_responses (
	challenge_response_id serial primary key,
	challenge_instance_id int references challenge_instances(challenge_instance_id),
	user_id int references users(user_id),
	filename text,
	mime_type text,
	caption text,
	created_at timestamp default current_timestamp
);
