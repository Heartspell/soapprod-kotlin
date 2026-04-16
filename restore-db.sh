#!/bin/bash
set -e

SA_PASSWORD="Admin@123456"
CONTAINER="soapprod-mssql"
BAK="/var/opt/mssql/backup/SoapProduction.bak"
SQLCMD="/opt/mssql-tools18/bin/sqlcmd"

run_sql() {
    docker exec "$CONTAINER" "$SQLCMD" -S localhost -U sa -P "$SA_PASSWORD" -C "$@"
}

echo "Starting container..."
docker compose up -d

echo "Waiting for SQL Server to be ready..."
for i in $(seq 1 40); do
    if run_sql -Q "SELECT 1" &>/dev/null; then
        echo "SQL Server is ready!"
        break
    fi
    echo "  Attempt $i/40..."
    sleep 3
    if [ "$i" -eq 40 ]; then
        echo "Timed out waiting for SQL Server"
        exit 1
    fi
done

echo ""
echo "Restoring SoapProduction database..."
run_sql -Q "
DECLARE @t TABLE (
    LogicalName nvarchar(128), PhysicalName nvarchar(260), [Type] char(1),
    FileGroupName nvarchar(128), Size numeric(20,0), MaxSize numeric(20,0),
    FileId bigint, CreateLSN numeric(25,0), DropLSN numeric(25,0),
    UniqueId uniqueidentifier, ReadOnlyLSN numeric(25,0), ReadWriteLSN numeric(25,0),
    BackupSizeInBytes bigint, SourceBlockSize int, FileGroupId int,
    LogGroupGUID uniqueidentifier, DifferentialBaseLSN numeric(25,0),
    DifferentialBaseGUID uniqueidentifier, IsReadOnly bit, IsPresent bit,
    TDEThumbprint varbinary(32), SnapshotUrl nvarchar(360)
);
INSERT INTO @t EXEC('RESTORE FILELISTONLY FROM DISK=N''$BAK''');
DECLARE @sql nvarchar(max) = 'RESTORE DATABASE [SoapProduction] FROM DISK=N''$BAK'' WITH REPLACE';
DECLARE @i int = 1, @n nvarchar(128), @tp char(1), @dest nvarchar(260);
DECLARE c CURSOR FOR SELECT LogicalName, [Type] FROM @t;
OPEN c; FETCH NEXT FROM c INTO @n, @tp;
WHILE @@FETCH_STATUS = 0 BEGIN
    IF @tp = 'L'
        SET @dest = '/var/opt/mssql/data/SoapProduction_' + CAST(@i AS nvarchar) + '.ldf';
    ELSE
        SET @dest = '/var/opt/mssql/data/SoapProduction_' + CAST(@i AS nvarchar) + '.mdf';
    SET @sql = @sql + ', MOVE N''' + @n + ''' TO N''' + @dest + '''';
    SET @i = @i + 1;
    FETCH NEXT FROM c INTO @n, @tp;
END;
CLOSE c; DEALLOCATE c;
EXEC(@sql);
"

echo ""
echo "Creating 'administrator' login..."
run_sql -Q "
IF NOT EXISTS (SELECT * FROM sys.server_principals WHERE name = 'administrator')
    CREATE LOGIN [administrator] WITH PASSWORD = '123', CHECK_POLICY = OFF, CHECK_EXPIRATION = OFF;
ALTER SERVER ROLE sysadmin ADD MEMBER [administrator];
USE [SoapProduction];
IF NOT EXISTS (SELECT * FROM sys.database_principals WHERE name = 'administrator')
    CREATE USER [administrator] FOR LOGIN [administrator];
ALTER ROLE db_owner ADD MEMBER [administrator];
"

echo ""
echo "Applying application stored procedures..."
run_sql -d SoapProduction -i "/var/opt/mssql/backup/soapproduction-procedures.sql"

echo ""
echo "Done!"
echo "  Host:     localhost:1433"
echo "  Database: SoapProduction"
echo "  User:     administrator"
echo "  Password: 123"
