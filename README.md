# Same Day Different Lives

## Installation 

### Install requirements

* Java
* PostgreSQL
* FFMPEG (`brew install ffmpeg` on OSX)


### Create a user and database in PostgreSQL

On Ubuntu, PostgreSQL uses Linux user authentification:

```
sudo adduser same-day-different-lives

sudo -u postgres createuser -P same-day-different-lives
sudo -u postgres createdb --owner same-day-different-lives same-day-different-lives
```

### Initialize the database

```
sudo -u same-day-different-lives psql < init_db.sql
```


### Setup the configuration

Copy `config.sample.edn` to `config.edn` and change the database name, user, and password if necessary.


### Create uploads directory

Create a directory `uploads`. Make sure that the web server has write permissions


### Run 

On Ubuntu:

```
sudo -u same-day-different-lives PORT=3040 java -jar same-day-different-lives.jar
```


### Example Nginx configuration

Notice that this configuration removes the limit on client request sizes.

```
server {
       listen 80;
       listen [::]:80;

       server_name sddl.crigamelab.org;

       location / {
               client_max_body_size 0;
               proxy_pass http://localhost:3040/;
       }
}
```

