package com.example.pkspkms

import io.pskenny.pkspkms.Server
import io.pskenny.pkspkms.repo.SQLitePksFileRepository
import io.pskenny.pkspkms.repo.sqlite.SQLiteLuaConnector
import io.pskenny.pkspkms.io.VaultFileSystem
import io.pskenny.pkspkms.io.fs.JavaFileVaultFileSystem
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

        // Construct the vault filesystem. JavaFileVaultFileSystem wraps a
        // java.io.File root and provides listFiles/openInput/openOutput.
        //
        // For SAF (Storage Access Framework) based access, implement the
        // VaultFileSystem interface with DocumentFile + ContentResolver.
        // See the README for details.
        val vaultFs: VaultFileSystem = JavaFileVaultFileSystem(File(vaultPath))

        // Construct the repository. The second argument registers lua_eval
        // as a SQLite custom function via xerial's JNI binding — identical
        // to the desktop path. This enables searchWithLuaFilter() to push
        // Lua filters into SQL for in-database evaluation.
        //
        // WARNING: SQLiteSchema.init() currently DROPs and recreates all
        // tables on every new SQLitePksFileRepository construction. Keep
        // the repository as an app-lifetime singleton — do NOT construct it
        // inside an Activity or it will wipe the database on every
        // launch/rotation.
        val repository = SQLitePksFileRepository(
            "jdbc:sqlite:$dbPath",
            SQLiteLuaConnector.luaFunctionRegistrar(),
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