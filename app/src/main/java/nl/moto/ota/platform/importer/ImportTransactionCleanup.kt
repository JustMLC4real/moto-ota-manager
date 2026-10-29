package nl.moto.ota.platform.importer

import java.io.File
import java.io.IOException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Deletes only the current generated private transaction, never source/selection/backup files. */
internal object ImportTransactionCleanup {
    suspend fun<T> withCleanup(transactions:File,transaction:File,block:suspend()->T):T {
        var originalFailure:Throwable?=null
        try{return block()}
        catch(error:Throwable){originalFailure=error;throw error}
        finally{
            try{cleanup(transactions,transaction)}
            catch(error:Throwable){if(originalFailure!=null)originalFailure.addSuppressed(error)else throw error}
        }
    }

    fun cleanup(transactions:File,transaction:File){
        val parent=transactions.absoluteFile
        val target=transaction.absoluteFile
        require(parent.name=="transactions" && parent.parentFile?.name=="motorola-import")
        require(parent.canonicalFile==parent && Files.isDirectory(parent.toPath(),NOFOLLOW_LINKS))
        require(target.name.matches(Regex("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")) && target.parentFile==parent)
        if(!Files.exists(target.toPath(),NOFOLLOW_LINKS))return
        require(target.canonicalFile==target && Files.isDirectory(target.toPath(),NOFOLLOW_LINKS))
        val root=target.toPath()
        // Withdrawing this request is also the root copy's cancellation signal.
        // deleteIfExists removes a request symlink itself and never follows it.
        Files.deleteIfExists(root.resolve("request.json"))
        repeat(3){attempt ->
            try{
                // Default walkFileTree options do not follow symbolic links.
                Files.walkFileTree(root,object:SimpleFileVisitor<Path>(){
                    override fun preVisitDirectory(dir:Path,attrs:BasicFileAttributes):FileVisitResult {
                        require(dir.toFile().canonicalFile.toPath().startsWith(root) && !Files.isSymbolicLink(dir))
                        return FileVisitResult.CONTINUE
                    }
                    override fun visitFile(file:Path,attrs:BasicFileAttributes):FileVisitResult {
                        Files.deleteIfExists(file)
                        return FileVisitResult.CONTINUE
                    }
                    override fun visitFileFailed(file:Path,error:IOException):FileVisitResult {
                        if(error !is NoSuchFileException)throw error
                        return FileVisitResult.CONTINUE
                    }
                    override fun postVisitDirectory(dir:Path,error:IOException?):FileVisitResult {
                        if(error!=null && error !is NoSuchFileException)throw error
                        Files.deleteIfExists(dir)
                        return FileVisitResult.CONTINUE
                    }
                })
                return
            }catch(error:NoSuchFileException){if(!Files.exists(root,NOFOLLOW_LINKS))return;if(attempt==2)throw error}
            catch(error:DirectoryNotEmptyException){if(attempt==2)throw error}
            // A concurrently stopping helper may just have published its one temp
            // filename inside this same transaction. Retry only this exact root.
        }
    }
}
