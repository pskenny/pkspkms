# THIS IS ALPHA, DON'T USE

**PK**'**S** **P**ersonal **K**nowledge **M**anagement **S**ystem

A server that provides a single user API and command-line tool for getting metadata out of a zettlekasten style 
personal knowledge management directory.

## Running

Make sure you have Maven and JDK 17 installed and running correctly.

```shell
mvn clean package
mv target/pkspkms-*.jar ~/.pkspkms/bin/pkspkms.jar
# copy command to start server at port 3000
java -jar /home/user/.pkspkms/bin/pkspkms.jar -directory /directory/to/pkms/ -port 3000
```

## Development

Example query:

- `files/list?filePath=*md&!tags=Bookmark` includes files ending in ".md" and excludes files with a tags property starting in
"Bookmark" (e.g. "Bookmarks/things").
