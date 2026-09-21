"""数据库连接与建表（表结构与 docs/data-model.md 一致，幂等）"""
import pymysql
from config import DB_CONFIG

DDL_STATEMENTS = [
    """CREATE TABLE IF NOT EXISTS prod_line (
        id bigint AUTO_INCREMENT PRIMARY KEY,
        name varchar(64) NOT NULL,
        plant varchar(64) NOT NULL,
        create_time datetime DEFAULT CURRENT_TIMESTAMP NOT NULL,
        update_time datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP NOT NULL,
        is_delete tinyint DEFAULT 0 NOT NULL
    ) COMMENT='产线' collate utf8mb4_unicode_ci""",
    """CREATE TABLE IF NOT EXISTS prod_process (
        id bigint AUTO_INCREMENT PRIMARY KEY,
        line_id bigint NOT NULL,
        name varchar(64) NOT NULL,
        seq int NOT NULL,
        param_name varchar(64) NOT NULL,
        param_target double NOT NULL,
        param_usl double NOT NULL,
        param_lsl double NOT NULL,
        create_time datetime DEFAULT CURRENT_TIMESTAMP NOT NULL,
        update_time datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP NOT NULL,
        is_delete tinyint DEFAULT 0 NOT NULL,
        KEY idx_line (line_id)
    ) COMMENT='工序' collate utf8mb4_unicode_ci""",
    """CREATE TABLE IF NOT EXISTS prod_daily_metrics (
        id bigint AUTO_INCREMENT PRIMARY KEY,
        stat_date date NOT NULL,
        line_id bigint NOT NULL,
        plan_qty int NOT NULL,
        output_qty int NOT NULL,
        good_qty int NOT NULL,
        defect_qty int NOT NULL,
        available_time_min int NOT NULL,
        run_time_min int NOT NULL,
        ideal_cycle_min double NOT NULL,
        actual_cycle_min double NOT NULL,
        create_time datetime DEFAULT CURRENT_TIMESTAMP NOT NULL,
        update_time datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP NOT NULL,
        is_delete tinyint DEFAULT 0 NOT NULL,
        UNIQUE KEY uk_date_line (stat_date, line_id)
    ) COMMENT='产线日指标' collate utf8mb4_unicode_ci""",
    """CREATE TABLE IF NOT EXISTS prod_defect_record (
        id bigint AUTO_INCREMENT PRIMARY KEY,
        stat_date date NOT NULL,
        line_id bigint NOT NULL,
        process_id bigint NOT NULL,
        defect_code varchar(32) NOT NULL,
        defect_name varchar(64) NOT NULL,
        qty int NOT NULL,
        severity tinyint NOT NULL COMMENT '1轻微 2一般 3严重',
        create_time datetime DEFAULT CURRENT_TIMESTAMP NOT NULL,
        update_time datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP NOT NULL,
        is_delete tinyint DEFAULT 0 NOT NULL,
        KEY idx_date_line (stat_date, line_id)
    ) COMMENT='缺陷记录' collate utf8mb4_unicode_ci""",
    """CREATE TABLE IF NOT EXISTS prod_spc_sample (
        id bigint AUTO_INCREMENT PRIMARY KEY,
        stat_date date NOT NULL,
        process_id bigint NOT NULL,
        sample_no tinyint NOT NULL COMMENT '子组内序号 1-5',
        value double NOT NULL,
        create_time datetime DEFAULT CURRENT_TIMESTAMP NOT NULL,
        update_time datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP NOT NULL,
        is_delete tinyint DEFAULT 0 NOT NULL,
        KEY idx_date_process (stat_date, process_id)
    ) COMMENT='SPC样本' collate utf8mb4_unicode_ci""",
]


def get_connection():
    return pymysql.connect(**DB_CONFIG)


def init_db(connection):
    with connection.cursor() as cursor:
        for ddl in DDL_STATEMENTS:
            cursor.execute(ddl)
    connection.commit()
