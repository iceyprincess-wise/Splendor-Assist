package com.assistant.overlay.database;

import androidx.annotation.NonNull;
import androidx.room.DatabaseConfiguration;
import androidx.room.InvalidationTracker;
import androidx.room.RoomDatabase;
import androidx.room.RoomOpenHelper;
import androidx.room.migration.AutoMigrationSpec;
import androidx.room.migration.Migration;
import androidx.room.util.DBUtil;
import androidx.room.util.TableInfo;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.SupportSQLiteOpenHelper;
import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.Generated;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class TheaterDatabase_Impl extends TheaterDatabase {
  private volatile TheaterDao _theaterDao;

  @Override
  @NonNull
  protected SupportSQLiteOpenHelper createOpenHelper(@NonNull final DatabaseConfiguration config) {
    final SupportSQLiteOpenHelper.Callback _openCallback = new RoomOpenHelper(config, new RoomOpenHelper.Delegate(1) {
      @Override
      public void createAllTables(@NonNull final SupportSQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `match_analytics_theater` (`matchId` TEXT NOT NULL, `startTimestamp` INTEGER NOT NULL, `endTimestamp` INTEGER NOT NULL, `dvrVideoPath` TEXT NOT NULL, `isPermanentlySaved` INTEGER NOT NULL, `possessionPercentage` REAL NOT NULL, `longPassEfficiency` REAL NOT NULL, `defensiveInterceptions` INTEGER NOT NULL, `transitionSpeedMs` INTEGER NOT NULL, `errorTimelineJson` TEXT NOT NULL, PRIMARY KEY(`matchId`))");
        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)");
        db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '4a4e4c116d3f6d9f8e251ec07ecdfc18')");
      }

      @Override
      public void dropAllTables(@NonNull final SupportSQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS `match_analytics_theater`");
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onDestructiveMigration(db);
          }
        }
      }

      @Override
      public void onCreate(@NonNull final SupportSQLiteDatabase db) {
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onCreate(db);
          }
        }
      }

      @Override
      public void onOpen(@NonNull final SupportSQLiteDatabase db) {
        mDatabase = db;
        internalInitInvalidationTracker(db);
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onOpen(db);
          }
        }
      }

      @Override
      public void onPreMigrate(@NonNull final SupportSQLiteDatabase db) {
        DBUtil.dropFtsSyncTriggers(db);
      }

      @Override
      public void onPostMigrate(@NonNull final SupportSQLiteDatabase db) {
      }

      @Override
      @NonNull
      public RoomOpenHelper.ValidationResult onValidateSchema(
          @NonNull final SupportSQLiteDatabase db) {
        final HashMap<String, TableInfo.Column> _columnsMatchAnalyticsTheater = new HashMap<String, TableInfo.Column>(10);
        _columnsMatchAnalyticsTheater.put("matchId", new TableInfo.Column("matchId", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsMatchAnalyticsTheater.put("startTimestamp", new TableInfo.Column("startTimestamp", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsMatchAnalyticsTheater.put("endTimestamp", new TableInfo.Column("endTimestamp", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsMatchAnalyticsTheater.put("dvrVideoPath", new TableInfo.Column("dvrVideoPath", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsMatchAnalyticsTheater.put("isPermanentlySaved", new TableInfo.Column("isPermanentlySaved", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsMatchAnalyticsTheater.put("possessionPercentage", new TableInfo.Column("possessionPercentage", "REAL", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsMatchAnalyticsTheater.put("longPassEfficiency", new TableInfo.Column("longPassEfficiency", "REAL", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsMatchAnalyticsTheater.put("defensiveInterceptions", new TableInfo.Column("defensiveInterceptions", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsMatchAnalyticsTheater.put("transitionSpeedMs", new TableInfo.Column("transitionSpeedMs", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsMatchAnalyticsTheater.put("errorTimelineJson", new TableInfo.Column("errorTimelineJson", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysMatchAnalyticsTheater = new HashSet<TableInfo.ForeignKey>(0);
        final HashSet<TableInfo.Index> _indicesMatchAnalyticsTheater = new HashSet<TableInfo.Index>(0);
        final TableInfo _infoMatchAnalyticsTheater = new TableInfo("match_analytics_theater", _columnsMatchAnalyticsTheater, _foreignKeysMatchAnalyticsTheater, _indicesMatchAnalyticsTheater);
        final TableInfo _existingMatchAnalyticsTheater = TableInfo.read(db, "match_analytics_theater");
        if (!_infoMatchAnalyticsTheater.equals(_existingMatchAnalyticsTheater)) {
          return new RoomOpenHelper.ValidationResult(false, "match_analytics_theater(com.assistant.overlay.database.MatchAnalyticsEntity).\n"
                  + " Expected:\n" + _infoMatchAnalyticsTheater + "\n"
                  + " Found:\n" + _existingMatchAnalyticsTheater);
        }
        return new RoomOpenHelper.ValidationResult(true, null);
      }
    }, "4a4e4c116d3f6d9f8e251ec07ecdfc18", "ab16f6e102b0b1dd28a102dd9a51f21c");
    final SupportSQLiteOpenHelper.Configuration _sqliteConfig = SupportSQLiteOpenHelper.Configuration.builder(config.context).name(config.name).callback(_openCallback).build();
    final SupportSQLiteOpenHelper _helper = config.sqliteOpenHelperFactory.create(_sqliteConfig);
    return _helper;
  }

  @Override
  @NonNull
  protected InvalidationTracker createInvalidationTracker() {
    final HashMap<String, String> _shadowTablesMap = new HashMap<String, String>(0);
    final HashMap<String, Set<String>> _viewTables = new HashMap<String, Set<String>>(0);
    return new InvalidationTracker(this, _shadowTablesMap, _viewTables, "match_analytics_theater");
  }

  @Override
  public void clearAllTables() {
    super.assertNotMainThread();
    final SupportSQLiteDatabase _db = super.getOpenHelper().getWritableDatabase();
    try {
      super.beginTransaction();
      _db.execSQL("DELETE FROM `match_analytics_theater`");
      super.setTransactionSuccessful();
    } finally {
      super.endTransaction();
      _db.query("PRAGMA wal_checkpoint(FULL)").close();
      if (!_db.inTransaction()) {
        _db.execSQL("VACUUM");
      }
    }
  }

  @Override
  @NonNull
  protected Map<Class<?>, List<Class<?>>> getRequiredTypeConverters() {
    final HashMap<Class<?>, List<Class<?>>> _typeConvertersMap = new HashMap<Class<?>, List<Class<?>>>();
    _typeConvertersMap.put(TheaterDao.class, TheaterDao_Impl.getRequiredConverters());
    return _typeConvertersMap;
  }

  @Override
  @NonNull
  public Set<Class<? extends AutoMigrationSpec>> getRequiredAutoMigrationSpecs() {
    final HashSet<Class<? extends AutoMigrationSpec>> _autoMigrationSpecsSet = new HashSet<Class<? extends AutoMigrationSpec>>();
    return _autoMigrationSpecsSet;
  }

  @Override
  @NonNull
  public List<Migration> getAutoMigrations(
      @NonNull final Map<Class<? extends AutoMigrationSpec>, AutoMigrationSpec> autoMigrationSpecs) {
    final List<Migration> _autoMigrations = new ArrayList<Migration>();
    return _autoMigrations;
  }

  @Override
  public TheaterDao theaterDao() {
    if (_theaterDao != null) {
      return _theaterDao;
    } else {
      synchronized(this) {
        if(_theaterDao == null) {
          _theaterDao = new TheaterDao_Impl(this);
        }
        return _theaterDao;
      }
    }
  }
}
