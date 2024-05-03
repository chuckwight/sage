Sage is an AI-powered tutoring app for General Chemistry.
The project is owned and maintained by ChemVantage LLC

To run locally, but use the remote Datastore:
	cd into the directory containing pom.xml
	gcloud auth application-default login
	gcloud config set project PROJECT_ID
	mvn clean package exec:java

To deploy on Google Cloud:
	cd into the directory containing pom.xml
	gcloud auth application-default login
	gcloud config set project PROJECT_ID
	mvn clean package appengine:deploy
	
	To avoid the automatic gcloud CLI update, you can use gcloud app deploy instead.
	Also, if a cached image has been deleted on app engine, you may need to use
	gcloud app deploy --no-cache