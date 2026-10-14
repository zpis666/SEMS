# 本地 MySQL 8.0 账号认证兼容；保留现有口令、权限和业务数据。
# 在 pwsh 中运行：.\db-navicat-compat.ps1
[CmdletBinding()]
param(
    [ValidateSet('mysql_native_password', 'caching_sha2_password')]
    [string]$AuthenticationPlugin = 'mysql_native_password'
)

$ErrorActionPreference = 'Stop'
$containerName = 'sems-mysql'
$containerJson = & docker inspect $containerName
if ($LASTEXITCODE -ne 0) { throw '无法读取 sems-mysql 容器，请先启动本地 MySQL。' }
$containerInfo = ($containerJson | ConvertFrom-Json)[0]
if (-not $containerInfo.State.Running) { throw 'sems-mysql 未运行，请先启动本地 MySQL。' }
$mysqlEnv = @{}
foreach ($item in $containerInfo.Config.Env) {
    $pair = $item -split '=', 2
    $mysqlEnv[$pair[0]] = $pair[1]
}
$rootPassword = $mysqlEnv['MYSQL_ROOT_PASSWORD']
$appUser = $mysqlEnv['MYSQL_USER']
$appPassword = $mysqlEnv['MYSQL_PASSWORD']
if (-not $rootPassword -or -not $appUser -or -not $appPassword) {
    throw '容器缺少 MYSQL_ROOT_PASSWORD / MYSQL_USER / MYSQL_PASSWORD，未执行任何修改。'
}

function ConvertTo-SqlLiteral([string]$Value) {
    # 当前会话固定移除 NO_BACKSLASH_ESCAPES，按 MySQL 字符串规则转义。
    return "'" + $Value.Replace('\', '\\').Replace("'", "''") + "'"
}

$appUserLiteral = ConvertTo-SqlLiteral $appUser
$query = "SELECT User, Host FROM mysql.user WHERE User IN ('root', $appUserLiteral);"
$accounts = $query | & docker exec -i -e "MYSQL_PWD=$rootPassword" $containerName mysql -uroot --batch --skip-column-names
if ($LASTEXITCODE -ne 0) { throw '无法认证本地 root 账号，未执行账号修改。请核对容器口令与实际账号口令。' }
$statements = [System.Collections.Generic.List[string]]::new()
$statements.Add("SET SESSION sql_mode = REPLACE(@@SESSION.sql_mode, 'NO_BACKSLASH_ESCAPES', '');")
foreach ($account in $accounts) {
    $fields = $account -split "`t", 2
    $accountUser = $fields[0]
    $accountHost = $fields[1]
    $password = if ($accountUser -eq 'root') { $rootPassword } else { $appPassword }
    $statements.Add('ALTER USER ' + (ConvertTo-SqlLiteral $accountUser) + '@' +
        (ConvertTo-SqlLiteral $accountHost) + ' IDENTIFIED WITH ' + $AuthenticationPlugin +
        ' BY ' + (ConvertTo-SqlLiteral $password) + ';')
}
if ($statements.Count -lt 2) { throw '没有找到本地项目账号，未执行任何修改。' }
$statements.Add("SELECT User, Host, plugin FROM mysql.user WHERE User IN ('root', $appUserLiteral);")
($statements -join "`n") | & docker exec -i -e "MYSQL_PWD=$rootPassword" $containerName mysql -uroot --batch
if ($LASTEXITCODE -ne 0) { throw '认证配置修改失败，请检查上方 MySQL 错误与当前账号状态。' }
Write-Host '本地账号认证已更新；业务表、权限和数据均保留。'
