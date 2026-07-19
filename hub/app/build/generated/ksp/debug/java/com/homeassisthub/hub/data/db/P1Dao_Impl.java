package com.homeassisthub.hub.data.db;

import android.database.Cursor;
import android.os.CancellationSignal;
import androidx.annotation.NonNull;
import androidx.room.CoroutinesRoom;
import androidx.room.EntityInsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomSQLiteQuery;
import androidx.room.SharedSQLiteStatement;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.sqlite.db.SupportSQLiteStatement;
import java.lang.Class;
import java.lang.Exception;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import javax.annotation.processing.Generated;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class P1Dao_Impl implements P1Dao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<P1DataEntity> __insertionAdapterOfP1DataEntity;

  private final SharedSQLiteStatement __preparedStmtOfDeleteOlderThan;

  public P1Dao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfP1DataEntity = new EntityInsertionAdapter<P1DataEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR ABORT INTO `p1_readings` (`id`,`timestamp`,`powerW`,`voltageV`) VALUES (nullif(?, 0),?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final P1DataEntity entity) {
        statement.bindLong(1, entity.getId());
        statement.bindLong(2, entity.getTimestamp());
        statement.bindDouble(3, entity.getPowerW());
        statement.bindDouble(4, entity.getVoltageV());
      }
    };
    this.__preparedStmtOfDeleteOlderThan = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM p1_readings WHERE timestamp < ?";
        return _query;
      }
    };
  }

  @Override
  public Object insert(final P1DataEntity entity, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __insertionAdapterOfP1DataEntity.insert(entity);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object deleteOlderThan(final long olderThanEpochMillis,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeleteOlderThan.acquire();
        int _argIndex = 1;
        _stmt.bindLong(_argIndex, olderThanEpochMillis);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfDeleteOlderThan.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object getRecent(final int limit,
      final Continuation<? super List<P1DataEntity>> $completion) {
    final String _sql = "SELECT * FROM p1_readings ORDER BY timestamp DESC LIMIT ?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, limit);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<List<P1DataEntity>>() {
      @Override
      @NonNull
      public List<P1DataEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfTimestamp = CursorUtil.getColumnIndexOrThrow(_cursor, "timestamp");
          final int _cursorIndexOfPowerW = CursorUtil.getColumnIndexOrThrow(_cursor, "powerW");
          final int _cursorIndexOfVoltageV = CursorUtil.getColumnIndexOrThrow(_cursor, "voltageV");
          final List<P1DataEntity> _result = new ArrayList<P1DataEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final P1DataEntity _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final long _tmpTimestamp;
            _tmpTimestamp = _cursor.getLong(_cursorIndexOfTimestamp);
            final double _tmpPowerW;
            _tmpPowerW = _cursor.getDouble(_cursorIndexOfPowerW);
            final double _tmpVoltageV;
            _tmpVoltageV = _cursor.getDouble(_cursorIndexOfVoltageV);
            _item = new P1DataEntity(_tmpId,_tmpTimestamp,_tmpPowerW,_tmpVoltageV);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public Flow<List<P1DataEntity>> observeAll() {
    final String _sql = "SELECT * FROM p1_readings ORDER BY timestamp ASC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"p1_readings"}, new Callable<List<P1DataEntity>>() {
      @Override
      @NonNull
      public List<P1DataEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfTimestamp = CursorUtil.getColumnIndexOrThrow(_cursor, "timestamp");
          final int _cursorIndexOfPowerW = CursorUtil.getColumnIndexOrThrow(_cursor, "powerW");
          final int _cursorIndexOfVoltageV = CursorUtil.getColumnIndexOrThrow(_cursor, "voltageV");
          final List<P1DataEntity> _result = new ArrayList<P1DataEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final P1DataEntity _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final long _tmpTimestamp;
            _tmpTimestamp = _cursor.getLong(_cursorIndexOfTimestamp);
            final double _tmpPowerW;
            _tmpPowerW = _cursor.getDouble(_cursorIndexOfPowerW);
            final double _tmpVoltageV;
            _tmpVoltageV = _cursor.getDouble(_cursorIndexOfVoltageV);
            _item = new P1DataEntity(_tmpId,_tmpTimestamp,_tmpPowerW,_tmpVoltageV);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public Flow<List<P1DataEntity>> observeSince(final long sinceEpochMillis) {
    final String _sql = "SELECT * FROM p1_readings WHERE timestamp >= ? ORDER BY timestamp ASC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, sinceEpochMillis);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"p1_readings"}, new Callable<List<P1DataEntity>>() {
      @Override
      @NonNull
      public List<P1DataEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfTimestamp = CursorUtil.getColumnIndexOrThrow(_cursor, "timestamp");
          final int _cursorIndexOfPowerW = CursorUtil.getColumnIndexOrThrow(_cursor, "powerW");
          final int _cursorIndexOfVoltageV = CursorUtil.getColumnIndexOrThrow(_cursor, "voltageV");
          final List<P1DataEntity> _result = new ArrayList<P1DataEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final P1DataEntity _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final long _tmpTimestamp;
            _tmpTimestamp = _cursor.getLong(_cursorIndexOfTimestamp);
            final double _tmpPowerW;
            _tmpPowerW = _cursor.getDouble(_cursorIndexOfPowerW);
            final double _tmpVoltageV;
            _tmpVoltageV = _cursor.getDouble(_cursorIndexOfVoltageV);
            _item = new P1DataEntity(_tmpId,_tmpTimestamp,_tmpPowerW,_tmpVoltageV);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @NonNull
  public static List<Class<?>> getRequiredConverters() {
    return Collections.emptyList();
  }
}
