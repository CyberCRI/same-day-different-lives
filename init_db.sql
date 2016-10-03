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

DROP TYPE IF EXISTS user_gender CASCADE;
CREATE TYPE user_gender AS ENUM ('male', 'female', 'other');

DROP TABLE IF EXISTS religion CASCADE;
CREATE TABLE religion (
	religion_id int primary key,
	religion_name text
);
INSERT INTO religion (religion_id, religion_name) VALUES
	(0, 'None'),
	(1, 'Other'),
	(2, 'Hinduism'),
	(3, 'Islam'),
	(4, 'Christianity'),
	(5, 'Sikhism'),
	(6, 'Buddhism'),
	(7, 'Jainism'),
	(8, 'Judaism'); -- not a major religion in India, but elsewhere

DROP TABLE IF EXISTS region CASCADE;
CREATE TABLE region (
	region_id int primary key,
	region_name text
);
INSERT INTO region (region_id, region_name) VALUES
	(1, 'Other'),
	(2, 'East India'),
	(3, 'North India'),
	(4, 'Northeast India'),
	(5, 'South India'),
	(6, 'Western India'),
	(7, 'Islands of India');

DROP TABLE IF EXISTS education_level CASCADE;
CREATE TABLE education_level (
	education_level_id int primary key,
	education_level_name text 
);
INSERT INTO education_level (education_level_id, education_level_name) VALUES
	(0, 'None'),
	(1, 'Other'),
	(2, 'Primary school'),
	(3, 'High school'),
	(4, 'University'),
	(5, 'Masters degree'),
	(6, 'Doctoral degree');

DROP TYPE IF EXISTS user_skin_color CASCADE;
CREATE TYPE user_skin_color AS ENUM ('dark', 'in-between', 'light');

DROP TYPE IF EXISTS political_position CASCADE;
CREATE TYPE political_position AS ENUM ('liberal', 'moderate', 'conservative');

DROP TABLE IF EXISTS users CASCADE;
CREATE TABLE users (
	user_id serial primary key,
	pseudo text,
	email text unique,
	password text, -- encrypted
	status user_status default 'dormant',
	created_at timestamp default current_timestamp,

	-- Demographics: 
	gender user_gender,
	birth_year int,
	religion_id int references religion(religion_id),
	region_id int references region(region_id),
	skin_color user_skin_color,
	education_level_id education_level,
	politics_social political_position,
	politics_economics political_position
	-- TODO: Other possible criteria include language, ethnicity, caste, class / wealth 
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
