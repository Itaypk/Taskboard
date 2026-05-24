export type Priority = 'low' | 'medium' | 'high';
export type Status = 'todo' | 'done' | 'archived';

export const TAG_PALETTE = [
  { id: 'sage',   bg: '#E8F5EE', text: '#1A7A4A', border: '#BEE8D0' },
  { id: 'sky',    bg: '#EBF3FF', text: '#1D5FA8', border: '#BCD9FF' },
  { id: 'coral',  bg: '#FFF0EB', text: '#C83218', border: '#FFCAB6' },
  { id: 'amber',  bg: '#FFF8E0', text: '#A86000', border: '#FFE29A' },
  { id: 'violet', bg: '#F4EFFF', text: '#6230CC', border: '#D8C5FF' },
  { id: 'lemon',  bg: '#FDFCE0', text: '#777000', border: '#F0E888' },
  { id: 'teal',   bg: '#E4F9F8', text: '#0A7070', border: '#A6E4E2' },
  { id: 'rose',   bg: '#FFF0F5', text: '#B81850', border: '#FFBAD4' },
] as const;

export type TagColorId = typeof TAG_PALETTE[number]['id'];

export interface Tag {
  id: string;
  label: string;
  colorId: TagColorId;
}

export const PAPER_SWATCHES = [
  { id: 'sunshine', paper: '#F7E27A', edge: '#E5CE5E', ink: '#3A2F0B' },
  { id: 'blossom',  paper: '#F6B6B6', edge: '#E49898', ink: '#4A1E1E' },
  { id: 'mint',     paper: '#A8E0C2', edge: '#86C7A6', ink: '#143A27' },
  { id: 'sky',      paper: '#C6D8F2', edge: '#A4BDE0', ink: '#1B2E4D' },
  { id: 'lilac',    paper: '#E8D4F0', edge: '#CDB5DC', ink: '#3D1E47' },
  { id: 'peach',    paper: '#F5CFA4', edge: '#DFB285', ink: '#4A2A0E' },
  { id: 'cream',    paper: '#F2EAD3', edge: '#D9CFB3', ink: '#2E2814' },
  { id: 'sage',     paper: '#CFDCC3', edge: '#B0C2A0', ink: '#233118' },
  { id: 'rose',     paper: '#EFC6D1', edge: '#D7A3B2', ink: '#4A1B2E' },
  { id: 'sand',     paper: '#E8D9B8', edge: '#CEBD94', ink: '#3A2D10' },
] as const;

export type PaperSwatchId = typeof PAPER_SWATCHES[number]['id'];

export interface Category {
  id: string;
  label: string;
  swatchId: PaperSwatchId;
}

export interface Task {
  id: string;
  title: string;
  description?: string;
  url?: string;
  priority?: Priority;
  deadline?: string; // YYYY-MM-DD
  estimatedMinutes?: number;
  status: Status;
  categoryId: string;
  tags: Tag[];
  sortKey: string;
  createdAt: string;
  updatedAt?: string;
  lastScheduledInSessionId?: string | null;
  relevantFrom?: string; // YYYY-MM-DD
}

export interface TimeSlot {
  startIso: string;
  endIso: string;
  label?: string;
}

export interface PlanTask extends Task {
  slots: TimeSlot[];
  planNotes?: string;
}

export type PlanStatus = 'active' | 'completed' | 'abandoned';

export interface CurrentPlan {
  id: string;
  status: PlanStatus;
  startedAt: string;
  endedAt?: string | null;
  summary?: string | null;
  tasks: PlanTask[];
  weekStart: string;
  weekEnd: string;
}

export type TaskFilter = 'todo' | 'plan' | 'done' | 'all';

export interface LanguageOption {
  code: string;
  label: string;
}

export interface GenderOption {
  code: string;
  label: string;
}

export interface SettingsOptions {
  timeZones: string[];
  languages: LanguageOption[];
  genders: GenderOption[];
}

export interface UserSettings {
  displayName: string;
  contextBlock: string;
  timeZone: string;
  preferredLanguage: string;
  calendarInviteEmail: boolean;
  gender?: string;
  agentDescription?: string;
  planningCron?: string | null;
  weekStartDay?: string | null;
  autoArchiveDays?: number | null;
  email: string;
  emailVerified: boolean;
  categories: Category[];
}
