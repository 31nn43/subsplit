# Subsplit (simple version): plain Java, no Spring, no Maven

Needs only a JDK 17+ (`java -version` / `javac -version`).

## Run
Open a terminal in this folder, then:

    javac *.java
    java SubsplitServer

Open http://localhost:8080 in your browser. Data lives in memory and resets when you stop the program.
Run it from this folder, because the server reads `index.html` from the current directory.

## Files
- User, Group, Membership, Payment: the data shapes (Java records)
- Store: in-memory lists acting as the database
- BalanceService: the business rules (who owes what, PAID/PENDING/OVERDUE)
- SubsplitServer: HTTP routes using the JDK's built-in web server, returns JSON
- index.html: the browser UI, calls the API with fetch()
