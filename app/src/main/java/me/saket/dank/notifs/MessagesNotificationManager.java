package me.saket.dank.notifs;

import static java.util.Collections.unmodifiableSet;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.support.annotation.CheckResult;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationCompat.Action;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.app.RemoteInput;
import android.support.v4.content.ContextCompat;
import android.text.Html;

import net.dean.jraw.models.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.reactivex.Completable;
import io.reactivex.Single;
import me.saket.dank.R;
import me.saket.dank.di.Dank;
import me.saket.dank.utils.JrawUtils;
import me.saket.dank.utils.Markdown;
import me.saket.dank.utils.Strings;
import timber.log.Timber;

public class MessagesNotificationManager {

  private static final int NOTIF_ID_BUNDLE_SUMMARY = 100;
  private static final int P_INTENT_REQ_ID_SUMMARY_MARK_ALL_AS_SEEN = 200;
  private static final int P_INTENT_REQ_ID_SUMMARY_MARK_ALL_AS_READ = 201;
  private static final int P_INTENT_REQ_ID_MARK_AS_READ = 202;
  private static final int P_INTENT_REQ_ID_MARK_AS_SEEN = 203;
  private static final int P_INTENT_REQ_ID_DIRECT_REPLY = 204;
  private static final String BUNDLED_NOTIFS_KEY = "unreadMessages";

  private final SeenUnreadMessageIdStore seenMessageIdsStore;

  public MessagesNotificationManager(SeenUnreadMessageIdStore seenMessageIdsStore) {
    this.seenMessageIdsStore = seenMessageIdsStore;
  }

  /**
   * Remove messages whose notifications the user has already seen (by dismissing it).
   */
  @CheckResult
  public Single<List<Message>> filterUnseenMessages(List<Message> unfilteredMessages) {
    return seenMessageIdsStore.get()
        .map(seenMessageIds -> {
          List<Message> unseenMessages = new ArrayList<>(unfilteredMessages.size());

          for (Message unfilteredMessage : unfilteredMessages) {
            if (!seenMessageIds.contains(unfilteredMessage.getId())) {
              unseenMessages.add(unfilteredMessage);
            } else {
              Timber.w("Already seen: %s", unfilteredMessage.getBody());
            }
          }

          return Collections.unmodifiableList(unseenMessages);
        });
  }

  @CheckResult
  public Completable markMessageNotifAsSeen(List<String> messageIds) {
    return seenMessageIdsStore.get()
        .flatMapCompletable(existingSeenMessageIds -> {
          Set<String> updatedSeenMessages = new HashSet<>(existingSeenMessageIds.size() + messageIds.size());
          updatedSeenMessages.addAll(existingSeenMessageIds);
          updatedSeenMessages.addAll(messageIds);
          return seenMessageIdsStore.save(updatedSeenMessages);
        });
  }

  @CheckResult
  public Completable markMessageNotifAsSeen(Message message) {
    return markMessageNotifAsSeen(Collections.singletonList(message.getId()));
  }

  /**
   * Recycle <var>message</var>'s ID when it's no longer unread.
   */
  @CheckResult
  public Completable removeMessageNotifSeenStatus(Message message) {
    return seenMessageIdsStore.get()
        .map(oldSeenMessageIds -> {
          Set<String> updatedSeenMessageIds = new HashSet<>(oldSeenMessageIds.size());
          updatedSeenMessageIds.addAll(oldSeenMessageIds);
          updatedSeenMessageIds.remove(message.getId());
          return Collections.unmodifiableSet(updatedSeenMessageIds);
        })
        .toCompletable();
  }

  /**
   * Empty the seen message Ids when there are no more unread messages present.
   */
  @CheckResult
  public Completable removeAllMessageNotifSeenStatuses() {
    return seenMessageIdsStore.save(Collections.emptySet());
  }

  public static class SeenUnreadMessageIdStore {
    private final SharedPreferences sharedPreferences;

    private static final String KEY_SEEN_UNREAD_MESSAGES = "seenUnreadMessages";

    public SeenUnreadMessageIdStore(SharedPreferences sharedPreferences) {
      this.sharedPreferences = sharedPreferences;
    }

    /**
     * @param seenMessageIds IDs of unread messages whose notifications the user has already seen.
     */
    @CheckResult
    public Completable save(Set<String> seenMessageIds) {
      return Completable.fromAction(() -> sharedPreferences.edit().putStringSet(KEY_SEEN_UNREAD_MESSAGES, seenMessageIds).apply());
    }

    /**
     * @return Message IDs that the user has already seen.
     */
    @CheckResult
    public Single<Set<String>> get() {
      return Single.fromCallable(() -> {
        Set<String> seenMessageIdSet = sharedPreferences.getStringSet(KEY_SEEN_UNREAD_MESSAGES, Collections.emptySet());
        return unmodifiableSet(seenMessageIdSet);
      });
    }
  }

// ======== NOTIFICATION ======== //

  public Completable displayNotification(Context context, List<Message> unreadMessages) {
    return Completable.fromAction(() -> {
      String loggedInUserName = Dank.reddit().loggedInUserName();

      Comparator<Message> oldestMessageFirstComparator = (first, second) -> {
        Date firstDate = first.getCreated();
        Date secondDate = second.getCreated();

        if (firstDate.after(secondDate)) {
          return -1;
        } else if (secondDate.after(firstDate)) {
          return +1;
        } else {
          return 0;   // Equal
        }
      };
      List<Message> sortedMessages = new ArrayList<>(unreadMessages.size());
      sortedMessages.addAll(unreadMessages);
      Collections.sort(sortedMessages, oldestMessageFirstComparator);

      Timber.i("Creating notifs for:");
      for (Message sortedMessage : sortedMessages) {
        Timber.i("%s (%s)", sortedMessage.getBody(), sortedMessage.getCreated());
      }
      createNotification(context, Collections.unmodifiableList(sortedMessages), loggedInUserName);
    });
  }

  /**
   * Constructs bundled notifications for unread messages.
   */
  private void createNotification(Context context, List<Message> unreadMessages, String loggedInUserName) {
    NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);

    // This summary notification will only be used on < Nougat, where bundled notifications aren't supported.
    // Though, Android will still pick up some properties from it on Nougat, like the sound, vibration, icon, etc.
    // The style (InboxStyle, MessagingStyle) is dropped on Nougat.
    NotificationCompat.Builder summaryNotifBuilder = unreadMessages.size() == 1
        ? createSingleMessageSummaryNotifBuilder(context, unreadMessages.get(0), loggedInUserName)
        : createMultipleMessagesSummaryNotifBuilder(context, unreadMessages, loggedInUserName);

    Notification summaryNotification = summaryNotifBuilder
        .setGroup(BUNDLED_NOTIFS_KEY)
        .setGroupSummary(true)
        .setShowWhen(true)
        .setAutoCancel(true)
        .setColor(ContextCompat.getColor(context, R.color.notification_icon_color))
        .setCategory(Notification.CATEGORY_MESSAGE)
        .setDefaults(Notification.DEFAULT_ALL)
        .setOnlyAlertOnce(true)
        .build();
    notificationManager.notify(NOTIF_ID_BUNDLE_SUMMARY, summaryNotification);

    // Add bundled notifications (Nougat+).
    for (Message unreadMessage : unreadMessages) {
      int notificationId = createNotificationIdFor(unreadMessage);

      // Mark as read action.
      PendingIntent markAsReadPendingIntent = createMarkAsReadPendingIntent(context, unreadMessage, P_INTENT_REQ_ID_MARK_AS_READ + notificationId);
      Action markAsReadAction = new Action.Builder(0, context.getString(R.string.messagenotification_mark_as_read), markAsReadPendingIntent).build();

      // Direct reply action.
      Intent directReplyIntent = NotificationActionReceiver.createDirectReplyIntent(context, unreadMessage, Dank.jackson(), notificationId);
      PendingIntent directReplyPendingIntent = PendingIntent.getBroadcast(
          context,
          P_INTENT_REQ_ID_DIRECT_REPLY + notificationId,
          directReplyIntent,
          PendingIntent.FLAG_CANCEL_CURRENT
      );
      Action replyAction = new Action.Builder(0, context.getString(R.string.messagenotification_reply), directReplyPendingIntent)
          .addRemoteInput(new RemoteInput.Builder(NotificationActionReceiver.KEY_DIRECT_REPLY_MESSAGE)
              .setLabel(context.getString(R.string.messagenotification_reply_to_user, unreadMessage.getAuthor()))
              .build())
          .setAllowGeneratedReplies(true)
          .build();

      // Mark as seen on dismissal.
      PendingIntent deletePendingIntent = createMarkAsSeenPendingIntent(context, unreadMessage, P_INTENT_REQ_ID_MARK_AS_SEEN + notificationId);

      String markdownStrippedBody = Markdown.stripMarkdown(JrawUtils.getMessageBodyHtml(unreadMessage));

      Notification bundledNotification = new NotificationCompat.Builder(context)
          .setContentTitle(unreadMessage.getAuthor())
          .setContentText(markdownStrippedBody)
          .setStyle(new NotificationCompat.BigTextStyle().bigText(markdownStrippedBody))
          .setShowWhen(false)
          .setSmallIcon(R.mipmap.ic_launcher)
          .setGroup(BUNDLED_NOTIFS_KEY)
          .setAutoCancel(true)
          .setColor(ContextCompat.getColor(context, R.color.color_accent))
          .addAction(markAsReadAction)
          .addAction(replyAction)
          .setDeleteIntent(deletePendingIntent)
          .setCategory(Notification.CATEGORY_MESSAGE)
          .build();
      notificationManager.notify(notificationId, bundledNotification);
    }
  }

  /**
   * Create an "BigTextStyle" notification for a single unread messages.
   */
  private NotificationCompat.Builder createSingleMessageSummaryNotifBuilder(Context context, Message unreadMessage, String loggedInUserName) {
    // Mark as read action.
    PendingIntent markAsReadPendingIntent = createMarkAsReadPendingIntent(context, unreadMessage, P_INTENT_REQ_ID_SUMMARY_MARK_ALL_AS_READ);
    Action markAsReadAction = new Action.Builder(0, context.getString(R.string.messagenotification_mark_as_read), markAsReadPendingIntent).build();

    // Dismissal intent.
    Intent markAsSeenIntent = NotificationActionReceiver.createMarkAsSeenIntent(context, unreadMessage);
    PendingIntent deletePendingIntent = PendingIntent.getBroadcast(
        context,
        P_INTENT_REQ_ID_SUMMARY_MARK_ALL_AS_SEEN,
        markAsSeenIntent,
        PendingIntent.FLAG_CANCEL_CURRENT
    );

    // Update: Lol using some tags crashes Android's SystemUi. We'll have to remove all markdown tags.
    String markdownStrippedBody = Markdown.stripMarkdown(JrawUtils.getMessageBodyHtml(unreadMessage));

    return new NotificationCompat.Builder(context)
        .setContentTitle(unreadMessage.getAuthor())
        .setContentText(markdownStrippedBody)
        .setStyle(new NotificationCompat.BigTextStyle()
            .bigText(markdownStrippedBody)
            .setSummaryText(loggedInUserName))
        .setSmallIcon(R.mipmap.ic_launcher)
        .setDeleteIntent(deletePendingIntent)
        .addAction(markAsReadAction);
  }

  /**
   * Create an "InboxStyle" notification for multiple unread messages.
   */
  private NotificationCompat.Builder createMultipleMessagesSummaryNotifBuilder(Context context, List<Message> unreadMessages,
      String loggedInUserName)
  {
    // Create a "InboxStyle" summary notification for the bundled notifs, that will only be visible on < Nougat.
    NotificationCompat.InboxStyle messagingStyleBuilder = new NotificationCompat.InboxStyle();
    messagingStyleBuilder.setSummaryText(loggedInUserName);
    for (Message unreadMessage : unreadMessages) {
      CharSequence messageBodyWithMarkdown = Markdown.stripMarkdown(JrawUtils.getMessageBodyHtml(unreadMessage));
      String markdownStrippedBody = messageBodyWithMarkdown.toString();

      //noinspection deprecation
      messagingStyleBuilder.addLine(Html.fromHtml(context.getString(
          R.string.messagenotification_below_nougat_expanded_body_row,
          unreadMessage.getAuthor(),
          markdownStrippedBody
      )));
    }

    // Mark all as seen on summary notif dismissal.
    Intent markAllAsSeenIntent = NotificationActionReceiver.createMarkAllAsSeenIntent(context, unreadMessages);
    PendingIntent summaryDeletePendingIntent = PendingIntent.getBroadcast(
        context,
        P_INTENT_REQ_ID_SUMMARY_MARK_ALL_AS_SEEN,
        markAllAsSeenIntent,
        PendingIntent.FLAG_CANCEL_CURRENT
    );

    // Mark all as read action. Will only show up on < Nougat.
    Intent markAllAsReadIntent = NotificationActionReceiver.createMarkAllAsReadIntent(context, unreadMessages);
    PendingIntent markAllAsReadPendingIntent = PendingIntent.getBroadcast(
        context,
        P_INTENT_REQ_ID_SUMMARY_MARK_ALL_AS_READ,
        markAllAsReadIntent,
        PendingIntent.FLAG_CANCEL_CURRENT
    );
    Action markAllReadAction = new Action.Builder(0, context.getString(R.string.messagenotification_mark_all_as_read), markAllAsReadPendingIntent).build();

    // Notification body.
    // Using a Set to remove duplicate author names.
    Set<String> messageAuthors = new LinkedHashSet<>(unreadMessages.size());
    for (Message unreadMessage : unreadMessages) {
      messageAuthors.add(unreadMessage.getAuthor());
    }
    String notifBody = messageAuthors.size() == 1
        ? context.getString(R.string.messagenotification_below_nougat_body_from_single_author, messageAuthors.iterator().next())
        : Strings.concatenateWithCommaAndAnd(context.getResources(), messageAuthors);

    return new NotificationCompat.Builder(context)
        .setContentTitle(context.getString(R.string.messagenotification_below_nougat_multiple_messages_title, unreadMessages.size()))
        .setContentText(notifBody)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setStyle(messagingStyleBuilder)
        .setDeleteIntent(summaryDeletePendingIntent)
        .addAction(markAllReadAction);
  }

  private PendingIntent createMarkAsReadPendingIntent(Context context, Message unreadMessage, int requestId) {
    int notificationId = createNotificationIdFor(unreadMessage);
    Intent markAsReadIntent = NotificationActionReceiver.createMarkAsReadIntent(context, unreadMessage, Dank.jackson(), notificationId);
    return PendingIntent.getBroadcast(context, requestId, markAsReadIntent, PendingIntent.FLAG_CANCEL_CURRENT);
  }

  private PendingIntent createMarkAsSeenPendingIntent(Context context, Message unreadMessage, int requestId) {
    Intent markAsSeenIntent = NotificationActionReceiver.createMarkAsSeenIntent(context, unreadMessage);
    return PendingIntent.getBroadcast(context, requestId, markAsSeenIntent, PendingIntent.FLAG_CANCEL_CURRENT);
  }

  private int createNotificationIdFor(Message message) {
    return message.getId().hashCode();
  }

  @CheckResult
  public Completable dismissNotification(Context context, int notificationId) {
    return Completable.fromAction(() -> {
      Timber.i("dismissNotification %s", notificationId);

      if (notificationId == -1) {
        throw new IllegalStateException();
      }

      NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
      notificationManager.cancel(notificationId);
    });
  }

  /**
   * Dismiss the summary notification of a bundle so that everything gets dismissed.
   */
  @CheckResult
  public Completable dismissAllNotifications(Context context) {
    return Completable.fromAction(() -> {
      Timber.i("Dismissing all notifs");
      NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
      notificationManager.cancel(NOTIF_ID_BUNDLE_SUMMARY);
    });
  }

}
