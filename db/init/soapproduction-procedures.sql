USE [SoapProduction];
GO

CREATE OR ALTER PROCEDURE sp_SetupAuthProcedures
AS
BEGIN
    SET NOCOUNT ON;

    EXEC(N'
    CREATE OR ALTER PROCEDURE sp_SaveRolePermissions
        @RoleId    INT,
        @ModuleKey NVARCHAR(100),
        @CanEdit   BIT,
        @CanDelete BIT
    AS
    BEGIN
        SET NOCOUNT ON;
        IF EXISTS (
            SELECT 1 FROM RolePermissions
            WHERE RoleId = @RoleId AND ModuleKey = @ModuleKey
        )
            UPDATE RolePermissions
            SET CanEdit = @CanEdit, CanDelete = @CanDelete
            WHERE RoleId = @RoleId AND ModuleKey = @ModuleKey;
        ELSE
            INSERT INTO RolePermissions (RoleId, ModuleKey, CanEdit, CanDelete)
            VALUES (@RoleId, @ModuleKey, @CanEdit, @CanDelete);
    END
    ');
END
GO

CREATE OR ALTER PROCEDURE sp_SetupCreditProcedures
AS
BEGIN
    SET NOCOUNT ON;

    EXEC(N'
    CREATE OR ALTER PROCEDURE sp_AddCredit
        @BankName   NVARCHAR(200),
        @Amount     FLOAT,
        @Rate       FLOAT,
        @TermMonths INT,
        @StartDate  DATE
    AS
    BEGIN
        SET NOCOUNT ON;
        DECLARE @MonthlyRate FLOAT = @Rate / 100.0 / 12.0;
        DECLARE @MonthlyPayment FLOAT;

        IF @MonthlyRate = 0 OR @TermMonths = 0
            SET @MonthlyPayment = CASE
                WHEN @TermMonths > 0 THEN @Amount / @TermMonths
                ELSE @Amount
            END;
        ELSE
            SET @MonthlyPayment = @Amount * @MonthlyRate * POWER(1.0 + @MonthlyRate, @TermMonths)
                                 / (POWER(1.0 + @MonthlyRate, @TermMonths) - 1.0);

        INSERT INTO Credits (
            BankName, Amount, Rate, TermMonths, StartDate,
            MonthlyPayment, RemainingAmount, IsActive
        )
        VALUES (
            @BankName, @Amount, @Rate, @TermMonths, @StartDate,
            @MonthlyPayment, @Amount, 1
        );

        SELECT CAST(SCOPE_IDENTITY() AS INT) AS Id;
    END
    ');
END
GO

CREATE OR ALTER PROCEDURE sp_EnsureProductionRequestSchema
AS
BEGIN
    SET NOCOUNT ON;

    IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = 'ProductionRequests')
    BEGIN
        CREATE TABLE ProductionRequests (
            Id INT IDENTITY(1,1) PRIMARY KEY,
            CreatedAt DATETIME2 NOT NULL DEFAULT GETDATE(),
            UpdatedAt DATETIME2 NOT NULL DEFAULT GETDATE(),
            Status NVARCHAR(100) NOT NULL DEFAULT N'Created',
            ApplicantName NVARCHAR(200) NOT NULL,
            ProductId INT NOT NULL,
            Quantity FLOAT NOT NULL DEFAULT 1,
            RejectionReason NVARCHAR(MAX) NULL
        );
    END;

    EXEC(N'
    CREATE OR ALTER PROCEDURE sp_ListProductionRequests
    AS
    BEGIN
        SET NOCOUNT ON;
        SELECT
            r.Id,
            r.CreatedAt,
            r.UpdatedAt,
            r.Status,
            r.ApplicantName,
            r.ProductId,
            r.Quantity,
            r.RejectionReason,
            p.Name AS ProductName
        FROM ProductionRequests r
        LEFT JOIN FinishedProducts p ON p.Id = r.ProductId
        ORDER BY r.CreatedAt DESC;
    END
    ');

    EXEC(N'
    CREATE OR ALTER PROCEDURE sp_GetProductionRequest
        @Id INT
    AS
    BEGIN
        SET NOCOUNT ON;
        SELECT
            r.Id,
            r.CreatedAt,
            r.UpdatedAt,
            r.Status,
            r.ApplicantName,
            r.ProductId,
            r.Quantity,
            r.RejectionReason,
            p.Name AS ProductName
        FROM ProductionRequests r
        LEFT JOIN FinishedProducts p ON p.Id = r.ProductId
        WHERE r.Id = @Id;
    END
    ');

    EXEC(N'
    CREATE OR ALTER PROCEDURE sp_CreateProductionRequest
        @ApplicantName NVARCHAR(200),
        @ProductId INT,
        @Quantity FLOAT
    AS
    BEGIN
        SET NOCOUNT ON;
        INSERT INTO ProductionRequests (
            ApplicantName, ProductId, Quantity, Status, CreatedAt, UpdatedAt
        )
        VALUES (
            @ApplicantName, @ProductId, @Quantity, N''Created'', GETDATE(), GETDATE()
        );

        SELECT CAST(SCOPE_IDENTITY() AS INT) AS Id;
    END
    ');

    EXEC(N'
    CREATE OR ALTER PROCEDURE sp_UpdateProductionRequestStatus
        @Id INT,
        @Status NVARCHAR(100),
        @RejectionReason NVARCHAR(MAX) = NULL
    AS
    BEGIN
        SET NOCOUNT ON;
        UPDATE ProductionRequests
        SET Status = @Status,
            RejectionReason = @RejectionReason,
            UpdatedAt = GETDATE()
        WHERE Id = @Id;
    END
    ');

    EXEC(N'
    CREATE OR ALTER PROCEDURE sp_DeleteProductionRequest
        @Id INT
    AS
    BEGIN
        SET NOCOUNT ON;
        DELETE FROM ProductionRequests WHERE Id = @Id;
    END
    ');
END
GO
