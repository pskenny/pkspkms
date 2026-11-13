package com.example.pkspkms

import io.pskenny.pkspkms.Server
import io.pskenny.pkspkms.io.fs.JavaFileSystem
import io.pskenny.pkspkms.io.fs.PkmsFileSystem
import io.pskenny.pkspkms.repo.sqlite.SQLitePksFileRepository
import java.io.File

object PkspkmsAndroid {

    fun init(
        packageName: String,
        vaultPath: String,
        port: Int = 3000
    ): Pair<SQLitePksFileRepository, Server> {

        // Android's DriverManager does not auto-discover JDBC drivers via
        // ServiceLoader/SPI the way desktop JVMs do. Explicitly load the class
        // so its static initializer runs and registers the driver.
        Class.forName("org.sqlite.JDBC")

        // SQLite needs the database file's parent directory to exist.
        val dbPath = "/data/data/$packageName/databases/pkspkms.db"
        File(dbPath).parentFile?.mkdirs()

        // Construct the vault filesystem. JavaFileSystem wraps a java.io.File
        // root and provides listFiles/resolve/openInput/openOutput.
        //
        // For SAF (Storage Access Framework) based access, implement the
        // PkmsFileSystem interface with ContentResolver + DocumentsContract
        // yourself. See the README for details.
        val vaultFs: PkmsFileSystem = JavaFileSystem(File(vaultPath))

        // Construct the repository. The registrar argument is null: embed
        // filters evaluate in-process (pure-Java luaj), so nothing is
        // registered on the connection.
        //
        // The SQLite schema is persistent and versioned in core; opening an
        // older index migrates it forward. Keep the repository as an
        // app-lifetime singleton anyway: one JDBC connection per session.
        val repository = SQLitePksFileRepository(
            "jdbc:sqlite:$dbPath",
            null,
            vaultFs
        )

        // Walk the vault directory and populate the database. On first run
        // this parses every markdown file. On subsequent runs it is
        // incremental (only new/modified files are re-parsed, deleted files
        // are removed).
        repository.loadDirectoryIntoRepository()

        // Start the HTTP server (optional). Run on a background thread,
        // never on the UI thread.
        val server = Server(port, repository)
        server.start(60000, false)

        return repository to server
    }

    fun shutdown(repository: SQLitePksFileRepository, server: Server) {
        server.stop()
        repository.close()
    }
}
