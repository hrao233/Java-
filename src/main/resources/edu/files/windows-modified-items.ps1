$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)

$connection = [System.Data.OleDb.OleDbConnection]::new("Provider=Search.CollatorDSO;Extended Properties='Application=Windows';")
try {
    $connection.Open()
    $command = $connection.CreateCommand()
    try {
        $command.CommandText = "SELECT TOP 256 System.ItemPathDisplay FROM SystemIndex WHERE SCOPE='file:' AND System.DateModified IS NOT NULL ORDER BY System.DateModified DESC"
        $reader = $command.ExecuteReader()
        try {
            while ($reader.Read()) {
                if (!$reader.IsDBNull(0)) { $reader.GetString(0) }
            }
        } finally {
            $reader.Dispose()
        }
    } finally {
        $command.Dispose()
    }
} finally {
    $connection.Dispose()
}
