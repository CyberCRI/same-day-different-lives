# Same Day Different Lives

Meet people in reverse. A prototype for the [IncLudo project on diversity](http://includo.in). 

Try it online at http://sddl.crigamelab.org/

Programmed in Clojure and ClojureScript.


## Installation 

### Install requirements

* Java
* PostgreSQL
* FFMPEG (`brew install ffmpeg` on OSX)
* ImageMagick (`brew install imagemagick` on OSX)


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


### Deploying

Check out the `deploy` folder for an example nginx configuration. Notice that this configuration removes the limit on client request sizes. 

The `deploy` folder also includes an example systemd configuration. 

