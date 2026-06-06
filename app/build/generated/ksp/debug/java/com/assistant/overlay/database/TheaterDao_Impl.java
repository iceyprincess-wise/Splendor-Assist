package com.assistant.overlay.database;

import android.database.Cursor;
import androidx.annotation.NonNull;
import androidx.room.EntityInsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomSQLiteQuery;
import androidx.room.SharedSQLiteStatement;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.sqlite.db.SupportSQLiteStatement;
import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.processing.Generated;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class TheaterDao_Impl implements TheaterDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<MatchAnalyticsEntity> __insertionAdapterOfMatchAnalyticsEntity;

  private final SharedSQLiteStatement __preparedStmtOfDropMatchRecord;

  public TheaterDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfMatchAnalyticsEntity = new EntityInsertionAdapter<MatchAnalyticsEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR REPLACE INTO `match_analytics_theater` (`matchId`,`startTimestamp`,`endTimestamp`,`dvrVideoPath`,`isPermanentlySaved`,`possessionPercentage`,`longPassEfficiency`,`defensiveInterceptions`,`transitionSpeedMs`,`errorTimelineJson`) VALUES (?,?,?,?,?,?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final MatchAnalyticsEntity entity) {
        statement.bindString(1, entity.getMatchId());
        statement.bindLong(2, entity.getStartTimestamp());
        statement.bindLong(3, entity.getEndTimestamp());
        statement.bindString(4, entity.getDvrVideoPath());
        final int _tmp = entity.isPermanentlySaved() ? 1 : 0;
        statement.bindLong(5, _tmp);
        statement.bindDouble(6, entity.getPossessionPercentage());
        statement.bindDouble(7, entity.getLongPassEfficiency());
        statement.bindLong(8, entity.getDefensiveInterceptions());
        statement.bindLong(9, entity.getTransitionSpeedMs());
        statement.bindString(10, entity.getErrorTimelineJson());
      }
    };
    this.__preparedStmtOfDropMatchRecord = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM match_analytics_theater WHERE matchId = ?";
        return _query;
      }
    };
  }

  @Override
  public void insertMatchData(final MatchAnalyticsEntity match) {
    __db.assertNotSuspendingTransaction();
    __db.beginTransaction();
    try {
      __insertionAdapterOfMatchAnalyticsEntity.insert(match);
      __db.setTransactionSuccessful();
    } finally {
      __db.endTransaction();
    }
  }

  @Override
  public void dropMatchRecord(final String matchId) {
    __db.assertNotSuspendingTransaction();
    final SupportSQLiteStatement _stmt = __preparedStmtOfDropMatchRecord.acquire();
    int _argIndex = 1;
    _stmt.bindString(_argIndex, matchId);
    try {
      __db.beginTransaction();
      try {
        _stmt.executeUpdateDelete();
        __db.setTransactionSuccessful();
      } finally {
        __db.endTransaction();
      }
    } finally {
      __preparedStmtOfDropMatchRecord.release(_stmt);
    }
  }

  @Override
  public List<MatchAnalyticsEntity> getActiveTheaterMatches() {
    final String _sql = "SELECT * FROM match_analytics_theater WHERE isPermanentlySaved = 0 ORDER BY endTimestamp DESC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    __db.assertNotSuspendingTransaction();
    final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
    try {
      final int _cursorIndexOfMatchId = CursorUtil.getColumnIndexOrThrow(_cursor, "matchId");
      final int _cursorIndexOfStartTimestamp = CursorUtil.getColumnIndexOrThrow(_cursor, "startTimestamp");
      final int _cursorIndexOfEndTimestamp = CursorUtil.getColumnIndexOrThrow(_cursor, "endTimestamp");
      final int _cursorIndexOfDvrVideoPath = CursorUtil.getColumnIndexOrThrow(_cursor, "dvrVideoPath");
      final int _cursorIndexOfIsPermanentlySaved = CursorUtil.getColumnIndexOrThrow(_cursor, "isPermanentlySaved");
      final int _cursorIndexOfPossessionPercentage = CursorUtil.getColumnIndexOrThrow(_cursor, "possessionPercentage");
      final int _cursorIndexOfLongPassEfficiency = CursorUtil.getColumnIndexOrThrow(_cursor, "longPassEfficiency");
      final int _cursorIndexOfDefensiveInterceptions = CursorUtil.getColumnIndexOrThrow(_cursor, "defensiveInterceptions");
      final int _cursorIndexOfTransitionSpeedMs = CursorUtil.getColumnIndexOrThrow(_cursor, "transitionSpeedMs");
      final int _cursorIndexOfErrorTimelineJson = CursorUtil.getColumnIndexOrThrow(_cursor, "errorTimelineJson");
      final List<MatchAnalyticsEntity> _result = new ArrayList<MatchAnalyticsEntity>(_cursor.getCount());
      while (_cursor.moveToNext()) {
        final MatchAnalyticsEntity _item;
        final String _tmpMatchId;
        _tmpMatchId = _cursor.getString(_cursorIndexOfMatchId);
        final long _tmpStartTimestamp;
        _tmpStartTimestamp = _cursor.getLong(_cursorIndexOfStartTimestamp);
        final long _tmpEndTimestamp;
        _tmpEndTimestamp = _cursor.getLong(_cursorIndexOfEndTimestamp);
        final String _tmpDvrVideoPath;
        _tmpDvrVideoPath = _cursor.getString(_cursorIndexOfDvrVideoPath);
        final boolean _tmpIsPermanentlySaved;
        final int _tmp;
        _tmp = _cursor.getInt(_cursorIndexOfIsPermanentlySaved);
        _tmpIsPermanentlySaved = _tmp != 0;
        final float _tmpPossessionPercentage;
        _tmpPossessionPercentage = _cursor.getFloat(_cursorIndexOfPossessionPercentage);
        final float _tmpLongPassEfficiency;
        _tmpLongPassEfficiency = _cursor.getFloat(_cursorIndexOfLongPassEfficiency);
        final int _tmpDefensiveInterceptions;
        _tmpDefensiveInterceptions = _cursor.getInt(_cursorIndexOfDefensiveInterceptions);
        final long _tmpTransitionSpeedMs;
        _tmpTransitionSpeedMs = _cursor.getLong(_cursorIndexOfTransitionSpeedMs);
        final String _tmpErrorTimelineJson;
        _tmpErrorTimelineJson = _cursor.getString(_cursorIndexOfErrorTimelineJson);
        _item = new MatchAnalyticsEntity(_tmpMatchId,_tmpStartTimestamp,_tmpEndTimestamp,_tmpDvrVideoPath,_tmpIsPermanentlySaved,_tmpPossessionPercentage,_tmpLongPassEfficiency,_tmpDefensiveInterceptions,_tmpTransitionSpeedMs,_tmpErrorTimelineJson);
        _result.add(_item);
      }
      return _result;
    } finally {
      _cursor.close();
      _statement.release();
    }
  }

  @Override
  public List<MatchAnalyticsEntity> getExpiredMatchesForDeletion(final long expirationEpoch) {
    final String _sql = "SELECT * FROM match_analytics_theater WHERE endTimestamp < ? AND isPermanentlySaved = 0";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, expirationEpoch);
    __db.assertNotSuspendingTransaction();
    final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
    try {
      final int _cursorIndexOfMatchId = CursorUtil.getColumnIndexOrThrow(_cursor, "matchId");
      final int _cursorIndexOfStartTimestamp = CursorUtil.getColumnIndexOrThrow(_cursor, "startTimestamp");
      final int _cursorIndexOfEndTimestamp = CursorUtil.getColumnIndexOrThrow(_cursor, "endTimestamp");
      final int _cursorIndexOfDvrVideoPath = CursorUtil.getColumnIndexOrThrow(_cursor, "dvrVideoPath");
      final int _cursorIndexOfIsPermanentlySaved = CursorUtil.getColumnIndexOrThrow(_cursor, "isPermanentlySaved");
      final int _cursorIndexOfPossessionPercentage = CursorUtil.getColumnIndexOrThrow(_cursor, "possessionPercentage");
      final int _cursorIndexOfLongPassEfficiency = CursorUtil.getColumnIndexOrThrow(_cursor, "longPassEfficiency");
      final int _cursorIndexOfDefensiveInterceptions = CursorUtil.getColumnIndexOrThrow(_cursor, "defensiveInterceptions");
      final int _cursorIndexOfTransitionSpeedMs = CursorUtil.getColumnIndexOrThrow(_cursor, "transitionSpeedMs");
      final int _cursorIndexOfErrorTimelineJson = CursorUtil.getColumnIndexOrThrow(_cursor, "errorTimelineJson");
      final List<MatchAnalyticsEntity> _result = new ArrayList<MatchAnalyticsEntity>(_cursor.getCount());
      while (_cursor.moveToNext()) {
        final MatchAnalyticsEntity _item;
        final String _tmpMatchId;
        _tmpMatchId = _cursor.getString(_cursorIndexOfMatchId);
        final long _tmpStartTimestamp;
        _tmpStartTimestamp = _cursor.getLong(_cursorIndexOfStartTimestamp);
        final long _tmpEndTimestamp;
        _tmpEndTimestamp = _cursor.getLong(_cursorIndexOfEndTimestamp);
        final String _tmpDvrVideoPath;
        _tmpDvrVideoPath = _cursor.getString(_cursorIndexOfDvrVideoPath);
        final boolean _tmpIsPermanentlySaved;
        final int _tmp;
        _tmp = _cursor.getInt(_cursorIndexOfIsPermanentlySaved);
        _tmpIsPermanentlySaved = _tmp != 0;
        final float _tmpPossessionPercentage;
        _tmpPossessionPercentage = _cursor.getFloat(_cursorIndexOfPossessionPercentage);
        final float _tmpLongPassEfficiency;
        _tmpLongPassEfficiency = _cursor.getFloat(_cursorIndexOfLongPassEfficiency);
        final int _tmpDefensiveInterceptions;
        _tmpDefensiveInterceptions = _cursor.getInt(_cursorIndexOfDefensiveInterceptions);
        final long _tmpTransitionSpeedMs;
        _tmpTransitionSpeedMs = _cursor.getLong(_cursorIndexOfTransitionSpeedMs);
        final String _tmpErrorTimelineJson;
        _tmpErrorTimelineJson = _cursor.getString(_cursorIndexOfErrorTimelineJson);
        _item = new MatchAnalyticsEntity(_tmpMatchId,_tmpStartTimestamp,_tmpEndTimestamp,_tmpDvrVideoPath,_tmpIsPermanentlySaved,_tmpPossessionPercentage,_tmpLongPassEfficiency,_tmpDefensiveInterceptions,_tmpTransitionSpeedMs,_tmpErrorTimelineJson);
        _result.add(_item);
      }
      return _result;
    } finally {
      _cursor.close();
      _statement.release();
    }
  }

  @NonNull
  public static List<Class<?>> getRequiredConverters() {
    return Collections.emptyList();
  }
}
