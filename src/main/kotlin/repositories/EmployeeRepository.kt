package repositories

import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Tuple
import models.Employee
import java.math.BigDecimal
import java.time.LocalDateTime

class EmployeeRepository(private val pool: Pool) {

    suspend fun listAll(): List<Employee> {
        val rows = pool.query("EXEC sp_ListEmployees").execute().coAwait()
        return rows.map { row ->
            Employee(
                id = row.requireInt("Id"),
                fullName = row.requireString("FullName"),
                dateOfBirth = row.getLocalDateTimeSafe("DateOfBirth"),
                positionId = row.getShortSafe("PositionID"),
                salary = row.getBigDecimalSafe("Salary"),
                homeAddress = row.getStringSafe("HomeAddress"),
                positionTitle = row.getStringSafe("PositionTitle")
            )
        }
    }

    suspend fun getById(id: Int): Employee? {
        val rows = pool.preparedQuery("EXEC sp_GetEmployee ?").execute(Tuple.of(id)).coAwait()
        val it = rows.iterator()
        if (!it.hasNext()) return null
        val row = it.next()
        return Employee(
            id = row.requireInt("Id"),
            fullName = row.requireString("FullName"),
            dateOfBirth = row.getLocalDateTimeSafe("DateOfBirth"),
            positionId = row.getShortSafe("PositionID"),
            salary = row.getBigDecimalSafe("Salary"),
            homeAddress = row.getStringSafe("HomeAddress"),
            positionTitle = row.getStringSafe("PositionTitle")
        )
    }

    suspend fun create(
        fullName: String, dateOfBirth: LocalDateTime?, positionId: Short?,
        salary: BigDecimal?, homeAddress: String?
    ): Int {
        val rows = pool.preparedQuery("EXEC sp_SaveEmployee ?, ?, ?, ?, ?, ?")
            .execute(Tuple.of(0, fullName, dateOfBirth, positionId?.toInt(), salary, homeAddress)).coAwait()
        return rows.iterator().next().requireInt("Id")
    }

    suspend fun update(employee: Employee): Int {
        val rows = pool.preparedQuery("EXEC sp_SaveEmployee ?, ?, ?, ?, ?, ?")
            .execute(Tuple.of(employee.id, employee.fullName, employee.dateOfBirth, employee.positionId?.toInt(), employee.salary, employee.homeAddress))
            .coAwait()
        val it = rows.iterator()
        return if (it.hasNext()) it.next().requireInt("Id") else employee.id
    }

    suspend fun delete(id: Int): Int =
        pool.preparedQuery("EXEC sp_DeleteEmployee ?").execute(Tuple.of(id)).coAwait().rowCount()
}
