import type { UserSettings } from './types';

export const DEFAULT_SETTINGS: UserSettings = {
  displayName: '',
  contextBlock: '',
  timeZone: 'UTC',
  preferredLanguage: 'en',
  calendarInviteEmail: false,
  appReminders: true,
  planningCron: null,
  weekStartDay: null,
  autoArchiveDays: null,
  aiEnabled: true,
  aiEnhancedReminders: true,
  aiTier: 'standard',
  aiTierGrantsAccess: true,
  email: '',
  emailVerified: false,
  categories: [],
};
