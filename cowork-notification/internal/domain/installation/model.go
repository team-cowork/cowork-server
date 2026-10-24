package installation

import "time"

type Platform string

const (
	PlatformIOS     Platform = "IOS"
	PlatformAndroid Platform = "ANDROID"
	PlatformWeb     Platform = "WEB"
)

// Installation is one Firebase app installation registered to an account. FID is the
// Firebase installation ID FCM delivers to; it is unique across accounts so a single
// app installation never has two owners.
type Installation struct {
	ID        int64     `gorm:"primaryKey;autoIncrement;column:id"`
	AccountID int64     `gorm:"column:account_id;not null;index:idx_tb_device_installation_account_id"`
	FID       string    `gorm:"column:fid;size:64;not null;uniqueIndex:uq_tb_device_installation_fid"`
	Platform  Platform  `gorm:"column:platform;size:20;not null"`
	CreatedAt time.Time `gorm:"column:created_at;autoCreateTime"`
	UpdatedAt time.Time `gorm:"column:updated_at;autoUpdateTime"`
}

type RegistrationResult struct {
	InstallationID    int64
	PreviousAccountID int64
	Reassigned        bool
}

func (Installation) TableName() string { return "tb_device_installation" }

type NotificationLevel string

const (
	NotificationLevelAll      NotificationLevel = "ALL"
	NotificationLevelMentions NotificationLevel = "MENTIONS"
	NotificationLevelNone     NotificationLevel = "NONE"
	// NotificationLevelDefault means the channel has no level of its own and follows the account and team levels.
	NotificationLevelDefault NotificationLevel = "DEFAULT"
)

// ChannelNotificationPreference is the notification choice an account made for one channel.
type ChannelNotificationPreference struct {
	Level      NotificationLevel
	MutedUntil *time.Time
}

func (p ChannelNotificationPreference) Muted(now time.Time) bool {
	return p.MutedUntil != nil && now.Before(*p.MutedUntil)
}

const StatusDoNotDisturb = "DO_NOT_DISTURB"

// DoNotDisturbSchedule is a daily quiet window in minutes after local midnight. A window
// whose end is earlier than its start crosses midnight, and an empty window never applies.
type DoNotDisturbSchedule struct {
	StartMinute int
	EndMinute   int
	Location    *time.Location
}

func (d DoNotDisturbSchedule) Contains(now time.Time) bool {
	local := now.In(d.Location)
	minute := local.Hour()*60 + local.Minute()
	if d.StartMinute <= d.EndMinute {
		return d.StartMinute <= minute && minute < d.EndMinute
	}
	return minute >= d.StartMinute || minute < d.EndMinute
}

// AccountNotificationSetting is the account-wide notification setting.
type AccountNotificationSetting struct {
	Level           *NotificationLevel
	Status          string
	StatusExpiresAt *time.Time
	Schedule        *DoNotDisturbSchedule
}

func (s AccountNotificationSetting) DoNotDisturb(now time.Time) bool {
	if s.Status == StatusDoNotDisturb && (s.StatusExpiresAt == nil || now.Before(*s.StatusExpiresAt)) {
		return true
	}
	return s.Schedule != nil && s.Schedule.Contains(now)
}

// RecipientPreferences holds what decides delivery for one notification's recipients.
type RecipientPreferences struct {
	Channels         map[int64]ChannelNotificationPreference
	Accounts         map[int64]AccountNotificationSetting
	TeamDefaultLevel *NotificationLevel
}

func (p RecipientPreferences) DoNotDisturb(accountID int64, now time.Time) bool {
	account, ok := p.Accounts[accountID]
	return ok && account.DoNotDisturb(now)
}

// ReceivesAllChannelMessages reports whether channel messages that do not mention the account
// reach it at now. A channel mute always applies, and the level resolves in channel, account,
// team default order, skipping a channel level of DEFAULT. Direct-message channels fall back to
// ALL because account and team levels describe team channels.
func (p RecipientPreferences) ReceivesAllChannelMessages(accountID int64, teamChannel bool, now time.Time) bool {
	if channel, ok := p.Channels[accountID]; ok {
		if channel.Muted(now) {
			return false
		}
		if channel.Level != NotificationLevelDefault {
			return channel.Level == NotificationLevelAll
		}
	}
	if !teamChannel {
		return true
	}
	if account, ok := p.Accounts[accountID]; ok && account.Level != nil {
		return *account.Level == NotificationLevelAll
	}
	if p.TeamDefaultLevel != nil {
		return *p.TeamDefaultLevel == NotificationLevelAll
	}
	return true
}
