/*
 * Copyright (c) 2024.
 * @Author Phel Viwath
 */

package sru.edu.sru_lib_management.core.data.repository

import io.r2dbc.spi.Row
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.*
import org.springframework.stereotype.Component
import sru.edu.sru_lib_management.core.data.query.BorrowQuery.BOOK_RETURN
import sru.edu.sru_lib_management.core.data.query.BorrowQuery.DELETE_BORROW_QUERY
import sru.edu.sru_lib_management.core.data.query.BorrowQuery.FIND_BORROW_BY_STUDENT_ID_BOOK_ID
import sru.edu.sru_lib_management.core.data.query.BorrowQuery.GET_BORROWS_QUERY
import sru.edu.sru_lib_management.core.data.query.BorrowQuery.GET_BORROW_QUERY
import sru.edu.sru_lib_management.core.data.query.BorrowQuery.SAVE_BORROW_QUERY
import sru.edu.sru_lib_management.core.data.query.BorrowQuery.UPDATE_BORROW_QUERY
import sru.edu.sru_lib_management.core.domain.dto.CompareValue
import sru.edu.sru_lib_management.core.domain.dto.analytic.MostBorrow
import sru.edu.sru_lib_management.core.domain.model.BorrowBook
import sru.edu.sru_lib_management.core.domain.repository.BorrowRepository
import sru.edu.sru_lib_management.utils.IndochinaDateTime.indoChinaDate
import java.sql.Date
import java.time.LocalDate

@Component
class BorrowRepositoryImp(
    private val client: DatabaseClient
) : BorrowRepository {
    override fun customBorrow(date: Date): Flow<BorrowBook> {
        TODO("Not yet implemented")
    }

    override suspend fun countBorrowPerWeek(): Map<LocalDate, Int> {
       return client.sql("CALL CountBorrowPerWeek()")
           .map { row ->
               val date = row.get("borrow_date", LocalDate::class.java)!!
               val count = row.get("count", Int::class.java)!!
               date to count
           }
           .all()
           .collectList()
           .awaitSingle()
           .toMap()
    }

    override suspend fun countCurrentAndPreviousBorrow(
        date: LocalDate, period: Int
    ): CompareValue {
        val param = mapOf(
            "date" to date,
            "period" to period
        )
        return client.sql("CALL CountBorrowByPeriod(:date, :period)")
            .bindValues(param)
            .map { row ->
                CompareValue(
                    row.get("current_value", Int::class.java)!!,
                    row.get("previous_value", Int::class.java)!!
                )
            }
            .one()
            .awaitSingle()
    }

    override suspend fun extendBorrow(borrowId: Long): Long {
        val rowEffected = client
            .sql("Update borrow_books set give_back_date = DATE_ADD(give_back_date, INTERVAL 2 WEEK), is_extend = true WHERE borrow_id = :borrowId")
            .bind("borrowId", borrowId)
            .fetch()
            .awaitRowsUpdated()
        return if (rowEffected > 0)
            borrowId
        else
            0L
    }

    override suspend fun save(entity: BorrowBook): BorrowBook {
        client.sql(SAVE_BORROW_QUERY)
            .bindValues(paramsMap(entity))
            .await()
        return entity
    }

    override suspend fun update(entity: BorrowBook): BorrowBook {
        client.sql(UPDATE_BORROW_QUERY)
            .bind("borrow_id", entity.borrowId)
            .bindValues(paramsMap(entity))
            .fetch()
            .awaitRowsUpdated()
        return entity
    }

    override suspend fun getById(id: Long): BorrowBook? = client.sql(GET_BORROW_QUERY)
            .bind("borrowId", id)
            .map { row: Row, _ ->
                row.rowMapping()
            }
            .awaitOneOrNull()

    override fun getAll(): Flow<BorrowBook> = client
        .sql(GET_BORROWS_QUERY)
        .map { row: Row, _ ->
            row.rowMapping()
        }
        .flow()


    override suspend fun delete(id: Long): Boolean {
        val rowEffected = client.sql(DELETE_BORROW_QUERY)
            .bind("borrow_id", id)
            .fetch()
            .awaitRowsUpdated()
        return rowEffected > 0
    }

    override fun findOverDueBook(): Flow<BorrowBook> {
        val query = "SELECT * FROM borrow_books where give_back_date <= curdate();"
        return client.sql(query)
            .map { row: Row, _ ->
                row.rowMapping()
            }
            .all()
            .asFlow()
    }

    override suspend fun bookReturned(borrowId: Long): Boolean {
        val rowEffected = client.sql(BOOK_RETURN)
            .bind("borrowId", borrowId)
            .bind("givBackDate", indoChinaDate())
            .fetch()
            .awaitRowsUpdated()
        return rowEffected > 0
    }

    override suspend fun findBorrowByStudentIdBookId(
        studentId: Long,
        bookId: String
    ): List<BorrowBook> {
        return client.sql(FIND_BORROW_BY_STUDENT_ID_BOOK_ID)
            .bind("studentId", studentId)
            .bind("bookId", bookId)
            .map { row: Row, _ ->
                row.rowMapping()
            }
            .flow()
            .toList()
    }

    override suspend fun getAllBorrowForEachMajor(
        startDate: LocalDate, endDate: LocalDate
    ): Flow<Map<String, Int>> {
        val query = """
            SELECT m.major_name AS major_name, COUNT(bb.borrow_id) AS borrow_count 
            FROM borrow_books bb 
            JOIN students s ON bb.student_id = s.student_id 
            JOIN majors m ON s.major_id = m.major_id 
            WHERE bb.borrow_date BETWEEN :startDate AND :endDate 
            GROUP BY m.major_name;
        """
        return client.sql(query)
            .bind("startDate", startDate)
            .bind("endDate", endDate)
            .map { row ->
                val majorName = row.get("major_name", String::class.java)!!
                val count = row.get("borrow_count", Int::class.java)!!
                mapOf(majorName to count)
            }
            .all()
            .asFlow()
    }

    override fun getMostBorrow(startDate: LocalDate, endDate: LocalDate): Flow<MostBorrow> {
        val query = """
            SELECT ROW_NUMBER() OVER (ORDER BY COUNT(b.book_id) DESC) AS ranking, 
               b.book_title AS bookTitle, 
               b.genre AS genre, 
               COUNT(b.book_id) AS count 
            FROM borrow_books bb 
            INNER JOIN books b ON bb.book_id = b.book_id 
            WHERE bb.borrow_date BETWEEN :startDate AND :endDate 
            GROUP BY b.book_id, b.book_title, b.genre 
            ORDER BY count DESC;
        """
        return client.sql(query)
            .bind("startDate", startDate)
            .bind("endDate", endDate)
            .map { row ->
                val rank = row.get("ranking", Int::class.java)
                val bookTitle = row.get("bookTitle", String::class.java)!!
                val genre = row.get("genre", String::class.java)!!
                val count = row.get("count", Int::class.java)!!
                MostBorrow(
                    rank = rank,
                    bookTitle = bookTitle,
                    genre = genre,
                    borrowQuan = count
                )
            }.flow()
    }

    private fun Row.rowMapping(): BorrowBook = BorrowBook(
        borrowId = this.get("borrow_id", Long::class.java)!!,
        bookId = this.get("book_id", String::class.java)!!,
        bookQuan = this.get("book_quan", Int::class.java)!!,
        studentId = this.get("student_id", Long::class.java)!!,
        borrowDate = this.get("borrow_date", LocalDate::class.java)!!,
        giveBackDate = this.get("give_back_date", LocalDate::class.java)!!,
        isBringBack = this.get("is_bring_back", Boolean::class.java)!!,
        isExtend = this.get("is_extend", Boolean::class.java)!!
    )

    private fun paramsMap(borrow: BorrowBook): Map<String, Any> = mapOf(
        "bookId" to borrow.bookId,
        "studentId" to borrow.studentId,
        "bookQuan" to borrow.bookQuan,
        "borrowDate" to borrow.borrowDate,
        "giveBackDate" to borrow.giveBackDate,
        "isBringBack" to borrow.isBringBack,
        "isExtend" to borrow.isExtend
    )
}
